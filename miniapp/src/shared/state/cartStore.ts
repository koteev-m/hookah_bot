import { transactRecord, updateRecordsWithPrefix } from './visitStorage'
import { getItemMeta, type ItemMeta, updateItemCache } from './itemCache'
import { getTelegramContext } from '../telegram'
import type { AddBatchRequest, CartMenuSelectionIssue, GiftDecisionDto, MenuItemOptionDto } from '../api/guestDtos'

export type CartLineOptionInput = {
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
  preferenceNote?: string | null
}

export type CartLineAddition = CartLineOptionInput & {
  itemId: number
  qty: number
}

export type CartLine = {
  key: string
  itemId: number
  qty: number
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
  preferenceNote?: string | null
}

export type CartSnapshot = {
  items: Map<string, CartLine>
  totalQty: number
  draftStorage: typeof draftStorage
  checkout: PreparedCheckout | null
  revision: number
  distinctCount: number
  commentDraft: string
  giftDecision: GiftDecisionDto | null
  menuSelectionIssues: Map<string, CartMenuSelectionIssue>
  pendingValidationLineRefs: Set<string>
}

export type CartGiftDecisionContext = {
  userId: number
  venueId: number
  tableSessionId: number
  tabId: number
  cartFingerprint: string
}

export type CartGiftDecisionOwnerContext = Omit<CartGiftDecisionContext, 'cartFingerprint'>

export type CartGiftDecisionScope = CartGiftDecisionContext & {
  expiresAtEpochSeconds: number
}

type ScopedGiftDecisionDraft = CartGiftDecisionScope & {
  decision: GiftDecisionDto
}

export type CartGiftDecisionReconcileResult = 'none' | 'active' | 'restored' | 'cleared'

type CartListener = (snapshot: CartSnapshot) => void

type SetQtyResult = {
  ok: boolean
  reason?: 'limit' | 'invalid' | 'storage'
}

type PersistedCartLine = {
  itemId: number
  qty: number
  selectedOptionId?: number | null
  selectedOptionName?: string | null
  priceDeltaMinor?: number | null
  preferenceNote?: string | null
}

type LegacyPersistedCartEntry = [number, number]

type PersistedDraft = {
  items: Array<LegacyPersistedCartEntry | PersistedCartLine>
  itemMeta?: ItemMeta[]
  commentDraft?: string
  giftDecisionDraft?: ScopedGiftDecisionDraft | null
  giftDecision?: unknown
}

const MAX_QTY = 50
const MAX_DISTINCT = 50
const MAX_PREFERENCE_NOTE_LENGTH = 200
const cartDraftLocalStoragePrefix = 'hookah_guest_cart_draft:'
const cartDraftLastUserStorageKey = 'hookah_guest_cart_draft:last_user'
const listeners = new Set<CartListener>()
let items = new Map<string, CartLine>()
let totalQty = 0
let commentDraft = ''
let giftDecision: GiftDecisionDto | null = null
let giftDecisionDraft: ScopedGiftDecisionDraft | null = null
let pendingGiftDecisionDraft: ScopedGiftDecisionDraft | null = null
let menuSelectionIssues = new Map<string, CartMenuSelectionIssue>()
let pendingValidationLineRefs = new Set<string>()
let activeDraftStorageKey: string | null = null
type DraftStatus = 'ready' | 'unread' | 'unsaved' | 'saving' | 'conflict' | 'capacity'
let draftStorage: DraftStatus = 'ready'

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

function normalizePreferenceNote(preferenceNote: string | null | undefined): string | null {
  const normalized = preferenceNote?.trim() ?? ''
  if (!normalized) {
    return null
  }
  return normalized.slice(0, MAX_PREFERENCE_NOTE_LENGTH)
}

function normalizeGiftDecision(value: GiftDecisionDto | null | undefined): GiftDecisionDto | null {
  if (!value || !['ACCEPT_FIXED', 'SELECT_ITEM', 'SKIP'].includes(value.action)) {
    return null
  }
  const decisionScopeToken = value.decisionScopeToken?.trim()
  const selectedMenuItemId = value.selectedMenuItemId == null ? null : Number(value.selectedMenuItemId)
  if (
    !decisionScopeToken ||
    (value.action === 'SELECT_ITEM' &&
      (selectedMenuItemId == null || !Number.isInteger(selectedMenuItemId) || selectedMenuItemId <= 0))
  ) {
    return null
  }
  return {
    action: value.action,
    decisionScopeToken,
    ...(value.action === 'SELECT_ITEM' ? { selectedMenuItemId } : {})
  }
}

function normalizePositiveId(value: unknown): number | null {
  const normalized = Number(value)
  return Number.isSafeInteger(normalized) && normalized > 0 ? normalized : null
}

function normalizeScopedGiftDecisionDraft(value: unknown): ScopedGiftDecisionDraft | null {
  if (!value || typeof value !== 'object') {
    return null
  }
  const draft = value as Partial<ScopedGiftDecisionDraft>
  const decision = normalizeGiftDecision(draft.decision)
  const userId = normalizePositiveId(draft.userId)
  const venueId = normalizePositiveId(draft.venueId)
  const tableSessionId = normalizePositiveId(draft.tableSessionId)
  const tabId = normalizePositiveId(draft.tabId)
  const cartFingerprint = typeof draft.cartFingerprint === 'string' ? draft.cartFingerprint.trim() : ''
  const expiresAtEpochSeconds = normalizePositiveId(draft.expiresAtEpochSeconds)
  if (
    !decision ||
    !userId ||
    !venueId ||
    !tableSessionId ||
    !tabId ||
    !cartFingerprint ||
    !expiresAtEpochSeconds
  ) {
    return null
  }
  return {
    decision,
    userId,
    venueId,
    tableSessionId,
    tabId,
    cartFingerprint,
    expiresAtEpochSeconds
  }
}

function clearGiftDecisionDraft(): void {
  giftDecision = null
  giftDecisionDraft = null
  pendingGiftDecisionDraft = null
}

function clearMenuSelectionRecoveryState(): void {
  menuSelectionIssues = new Map()
  pendingValidationLineRefs = new Set()
}

export function buildCartLineKey(itemId: number, selectedOptionId?: number | null, preferenceNote?: string | null): string {
  const optionPart = normalizeSelectedOptionId(selectedOptionId) ?? 'base'
  const notePart = encodeURIComponent(normalizePreferenceNote(preferenceNote) ?? '')
  return `${itemId}:${optionPart}:${notePart}`
}

function resolveLineOption(
  itemId: number,
  input?: CartLineOptionInput | null
): {
  selectedOptionId: number | null
  selectedOptionName: string | null
  priceDeltaMinor: number | null
  preferenceNote: string | null
} {
  const preferenceNote = normalizePreferenceNote(input?.preferenceNote)
  const selectedOptionId = normalizeSelectedOptionId(input?.selectedOptionId)
  if (selectedOptionId == null) {
    return {
      selectedOptionId: null,
      selectedOptionName: null,
      priceDeltaMinor: null,
      preferenceNote
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
    priceDeltaMinor,
    preferenceNote
  }
}

function buildLine(itemId: number, qty: number, input?: CartLineOptionInput | null, existing?: CartLine): CartLine {
  const option = resolveLineOption(itemId, input)
  const selectedOptionId = option.selectedOptionId ?? existing?.selectedOptionId ?? null
  const preferenceNote = option.preferenceNote ?? existing?.preferenceNote ?? null
  const key = buildCartLineKey(itemId, selectedOptionId, preferenceNote)
  return {
    key,
    itemId,
    qty,
    selectedOptionId,
    selectedOptionName: option.selectedOptionName ?? existing?.selectedOptionName ?? null,
    priceDeltaMinor: option.priceDeltaMinor ?? existing?.priceDeltaMinor ?? null,
    preferenceNote
  }
}

function getCurrentUserPart(): string {
  const userId = getTelegramContext().telegramUserId
  return userId ? `user:${userId}` : 'user:unknown'
}

function getDraftStorageKey(tableToken: string, sessionId: number, userPart: string, generation?: string): string {
  // The unversioned key belongs to the pre-migration archive, including for generation zero.
  const key = `${cartDraftLocalStoragePrefix}${userPart}:${tableToken}:session:${sessionId}`
  return key + ':visit:' + (generation ?? '0')
}

function clearPersistedGiftDecisionsForAccountSwitch(nextUserPart: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    const previousUserPart = window.localStorage.getItem(cartDraftLastUserStorageKey)
    if (previousUserPart && previousUserPart !== nextUserPart) {
      const prefix = cartDraftLocalStoragePrefix + previousUserPart + ':'
      void updateRecordsWithPrefix<DraftRecord>(prefix, record => {
        if (!record.draft.giftDecision && !record.draft.giftDecisionDraft) return false
        delete record.draft.giftDecision
        delete record.draft.giftDecisionDraft
        return true
      }).catch(() => { /* Gift use still requires server verification for its account and cart. */ })
      const draftKeys: string[] = []
      for (let index = 0; index < window.localStorage.length; index += 1) {
        const key = window.localStorage.key(index)
        if (key?.startsWith(cartDraftLocalStoragePrefix + previousUserPart + ':') && /:visit:(0|[1-9][0-9]*)$/.test(key)) {
          draftKeys.push(key)
        }
      }
      draftKeys.forEach((key) => {
        const raw = window.localStorage.getItem(key)
        if (!raw) return
        try {
          const parsed = JSON.parse(raw) as PersistedDraft
          if (!parsed || typeof parsed !== 'object' || (!('giftDecision' in parsed) && !('giftDecisionDraft' in parsed))) return
          delete parsed.giftDecision
          delete parsed.giftDecisionDraft
          window.localStorage.setItem(key, JSON.stringify(parsed))
        } catch {
          // Invalid cart drafts are ignored by the normal restore path.
        }
      })
    }
    window.localStorage.setItem(cartDraftLastUserStorageKey, nextUserPart)
  } catch {
    // LocalStorage is UX cache only.
  }
}

function currentDraft(): Draft {
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
  const payload: Draft = {
    items: Array.from(items.values()).map((line) => ({
      itemId: line.itemId,
      qty: line.qty,
      selectedOptionId: line.selectedOptionId ?? null,
      selectedOptionName: line.selectedOptionName ?? null,
      priceDeltaMinor: line.priceDeltaMinor ?? null,
      preferenceNote: line.preferenceNote ?? null
    }))
  }
  if (itemMeta.length > 0) {
    payload.itemMeta = itemMeta
  }
  if (commentDraft) {
    payload.commentDraft = commentDraft
  }
  const scopedGiftDecision = giftDecisionDraft ?? pendingGiftDecisionDraft
  if (scopedGiftDecision) {
    payload.giftDecisionDraft = {
      ...scopedGiftDecision,
      decision: { ...scopedGiftDecision.decision }
    }
  }
  return payload
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
        : null,
    preferenceNote: typeof entry.preferenceNote === 'string' ? normalizePreferenceNote(entry.preferenceNote) : null
  }
}

function decodeDraft(parsed: PersistedDraft) {
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
    const key = buildCartLineKey(itemId, line.selectedOptionId, line.preferenceNote)
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
          priceDeltaMinor: line.priceDeltaMinor,
          preferenceNote: line.preferenceNote
        },
        existing
      )
    )
  }
  const restoredMeta = Array.isArray(parsed.itemMeta) ? parsed.itemMeta : []
  const restoredComment = typeof parsed.commentDraft === 'string' ? parsed.commentDraft : ''
  return {
    items: restored,
    itemMeta: restoredMeta,
    commentDraft: restoredComment,
    pendingGiftDecisionDraft: normalizeScopedGiftDecisionDraft(parsed.giftDecisionDraft)
  }

}

function buildSnapshot(): CartSnapshot {
  const snapshotItems = Array.from(items.entries()).map(([key, line]): [string, CartLine] => [key, { ...line }])
  return {
    items: new Map(snapshotItems),
    totalQty: draftStorage === 'saving' && activeDraftStorageKey ? editors.get(activeDraftStorageKey)?.committedQty ?? totalQty : totalQty,
    draftStorage,
    checkout: activeDraftStorageKey ? editors.get(activeDraftStorageKey)?.checkout ?? null : null,
    revision: activeDraftStorageKey ? editors.get(activeDraftStorageKey)?.revision ?? 0 : 0,
    distinctCount: items.size,
    commentDraft,
    giftDecision: giftDecision ? { ...giftDecision } : null,
    menuSelectionIssues: new Map(
      Array.from(menuSelectionIssues.entries()).map(([key, issue]) => [key, { ...issue }])
    ),
    pendingValidationLineRefs: new Set(pendingValidationLineRefs)
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

type Draft = Omit<PersistedDraft, 'items'> & { items: PersistedCartLine[] }
export type PreparedCheckout = {
  key: string
  userId: number
  venueId: number
  tableId: number
  payload: AddBatchRequest
}
type CheckoutRecord = { operation: PreparedCheckout; reserved: Record<string, number> }
type DraftRecord = { draft: Draft; ended?: boolean; importedSource?: string; revision?: number; checkout?: CheckoutRecord }
type Edit = { before: Draft; after: Draft; additive: boolean; replacement?: { from: string; to: string } }
type Editor = { syncing?: Promise<void>; revision?: number; checkout?: PreparedCheckout; committedQty: number; key: string; local: Draft; edits: Edit[]; status: DraftStatus; busy: boolean; refresh?: boolean; conflict?: Draft }
const editors = new Map<string, Editor>()
const emptyDraft = (): Draft => ({ items: [] })
const channel = typeof BroadcastChannel === 'function' ? new BroadcastChannel('hookah-cart-changes') : null
const same = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right)
const lineKey = (line: PersistedCartLine) => buildCartLineKey(line.itemId, line.selectedOptionId, line.preferenceNote)

function migrateCart(raw: string | null): DraftRecord {
  if (!raw) return { draft: emptyDraft() }
  const stored: PersistedDraft = JSON.parse(raw)
  if (!Array.isArray(stored?.items)) throw new Error('Invalid cart')
  const lines = stored.items.map(normalizePersistedLine)
  if (lines.some(line => !line)) throw new Error('Invalid cart line')
  return { draft: { ...stored, items: lines as PersistedCartLine[] }, importedSource: raw }
}

function removeMigratedDraft(key: string) {
  try { window.localStorage.removeItem(key) } catch { /* The committed record wins on retry. */ }
}

async function withCart<T>(key: string, change: (record: DraftRecord) => T): Promise<T> {
  const { value, source } = await transactRecord(
    key,
    () => migrateCart(window.localStorage.getItem(key)),
    record => ({ value: change(record), source: record.importedSource })
  )
  if (source !== undefined) {
    try {
      // A closed WebView or a failed remove can interrupt cleanup after import.
      // A legacy writer may also have changed its copy in the meantime.
      if (window.localStorage.getItem(key) === source) window.localStorage.removeItem(key)
      await transactRecord<DraftRecord, void>(key, () => { throw new Error('Missing imported cart') }, record => {
        if (record.importedSource === source) delete record.importedSource
      })
    } catch { /* Retry on the next access. IndexedDB already owns the active draft. */ }
  }
  return value
}

// Compare only fields touched by this edit. Relative quantity changes commute,
// but removing or replacing a concurrently edited line requires a guest decision.
function mergeEdit(saved: Draft, edit: Edit, override = false): Draft | null {
  const { before, after } = edit
  const result = { ...saved }
  for (const field of ['commentDraft', 'giftDecisionDraft'] as const) {
    if (same(before[field], after[field])) continue
    if (!override && !same(saved[field], before[field]) && !same(saved[field], after[field])) return null
    Object.assign(result, { [field]: after[field] })
  }
  const base = new Map(before.items.map(line => [lineKey(line), line]))
  const next = new Map(after.items.map(line => [lineKey(line), line]))
  const lines = new Map(saved.items.map(line => [lineKey(line), line]))
  if (edit.replacement) {
    const { from, to } = edit.replacement
    const source = lines.get(from)
    // A different window may have moved or removed this physical line. Its successor
    // is ambiguous. Applying either half of the old move would duplicate an item.
    if (!source || (to !== from && lines.has(to))) return null
    if (!same(source, base.get(from)) && !override) return null
    const replacement = next.get(to)
    if (!replacement) return null
    lines.delete(from)
    lines.set(to, { ...replacement, qty: source.qty })
    base.delete(from)
    next.delete(to)
  }
  for (const key of new Set([...base.keys(), ...next.keys()])) {
    const old = base.get(key), desired = next.get(key), current = lines.get(key)
    if (same(old, desired)) continue
    if (edit.additive && (!old || current)) {
      let qty = (current?.qty ?? 0) + (desired?.qty ?? 0) - (old?.qty ?? 0)
      if (qty < 0 || qty > MAX_QTY) {
        if (!override) return null
        qty = desired?.qty ?? 0
      }
      if (qty === 0) lines.delete(key)
      else lines.set(key, { ...(desired ?? current!), qty })
    } else {
      if (!override && !same(current, old) && !same(current, desired)) return null
      if (desired) lines.set(key, desired)
      else lines.delete(key)
    }
  }
  if (lines.size > MAX_DISTINCT) return null
  result.items = [...lines.values()]
  result.itemMeta = [...new Map([...(saved.itemMeta ?? []), ...(after.itemMeta ?? [])].map(meta => [meta.itemId, meta])).values()]
  return result
}

function display(editor: Editor, replace = false) {
  if (activeDraftStorageKey !== editor.key) return
  draftStorage = editor.status
  if (replace) {
    const draft = decodeDraft(editor.local)
    items = draft.items
    commentDraft = draft.commentDraft
    if (!same(giftDecisionDraft ?? pendingGiftDecisionDraft, draft.pendingGiftDecisionDraft)) {
      clearGiftDecisionDraft()
      pendingGiftDecisionDraft = draft.pendingGiftDecisionDraft
    }
    if (draft.itemMeta.length) updateItemCache(draft.itemMeta)
    updateTotals()
  }
  notify()
}

function synchronize(editor: Editor, resolution?: { observed: Draft; useMine: boolean }): Promise<void> {
  if (editor.syncing) {
    editor.refresh = true
    return editor.syncing
  }
  return editor.syncing = runSynchronization(editor, resolution).finally(() => { editor.syncing = undefined })
}

async function runSynchronization(editor: Editor, resolution?: { observed: Draft; useMine: boolean }) {
  if (editor.busy) return
  editor.busy = true
  try {
    do {
      editor.refresh = false
      const edits = editor.edits.slice()
      const saved = await withCart(editor.key, record => {
        if (record.ended) return { draft: emptyDraft(), ended: true }
        let draft = record.draft
        const reserved = { ...record.checkout?.reserved }
        for (const edit of edits) {
          const resolved = resolution && same(resolution.observed, record.draft)
          const next = mergeEdit(draft, edit, !!resolved && resolution.useMine)
          if (next) {
            draft = next
            for (const key of Object.keys(reserved)) {
              reserved[key] = Math.min(reserved[key], next.items.find(line => lineKey(line) === key)?.qty ?? 0)
            }
          } else if (!resolved || resolution.useMine) return { draft: record.draft, conflict: true, capacity: !!resolved }
        }
        if (!same(record.draft, draft)) record.revision = (record.revision ?? 0) + 1
        record.draft = draft
        if (record.checkout) record.checkout.reserved = reserved
        return { draft, revision: record.revision, checkout: record.checkout?.operation }
      })
      if (!saved.conflict) {
        editor.revision = saved.revision ?? 0
        editor.checkout = saved.checkout
      }
      if (saved.conflict) {
        editor.conflict = saved.draft
        editor.status = saved.capacity ? 'capacity' : 'conflict'
        display(editor)
        return
      }
      editor.edits.splice(0, saved.ended ? editor.edits.length : edits.length)
      if (edits.length) channel?.postMessage(editor.key)
      if (!editor.edits.length) {
        editor.committedQty = saved.draft.items.reduce((sum, line) => sum + line.qty, 0)
        editor.local = saved.draft
        editor.status = saved.ended ? 'unread' : 'ready'
        display(editor, true)
      }
    } while (editor.edits.length || editor.refresh)
  } catch {
    editor.status = editor.edits.length ? 'unsaved' : 'unread'
    display(editor)
  } finally { editor.busy = false }
}

function persistActiveDraft(additive = false, replacement?: Edit['replacement']) {
  const editor = activeDraftStorageKey && editors.get(activeDraftStorageKey)
  if (!editor || !canEdit()) return
  const after = currentDraft()
  if (same(after, editor.local)) return
  editor.edits.push({ before: editor.local, after, additive, replacement })
  editor.local = after
  draftStorage = editor.status = 'saving'
  void synchronize(editor)
}

async function refreshCart(key: string) {
  const editor = editors.get(key)
  if (!editor || editor.conflict) return
  await synchronize(editor)
}

// Commit the immutable business request before transport. An old button cannot start
// another submission after a different window has already acknowledged this draft.
export async function prepareCheckout(userId: number, venueId: number, tableId: number, revision: number,
  payload: AddBatchRequest, retry: boolean): Promise<PreparedCheckout | null> {
  const key = getDraftStorageKey(payload.tableToken, payload.tableSessionId, 'user:' + userId, payload.expectedVisitGeneration)
  if (key !== activeDraftStorageKey || getTelegramContext().telegramUserId !== userId) return null
  const operation = await withCart(key, record => {
    if (record.ended) return null
    if (record.checkout) {
      const prior = record.checkout.operation
      return prior.venueId === venueId && prior.tableId === tableId &&
        (retry ? prior.payload.idempotencyKey === payload.idempotencyKey :
          same({ ...prior.payload, idempotencyKey: payload.idempotencyKey }, payload)) ? prior : null
    }
    if (retry || revision !== (record.revision ?? 0) || !record.draft.items.length) return null
    const prepared = { key, userId, venueId, tableId, payload }
    record.checkout = { operation: prepared, reserved: Object.fromEntries(record.draft.items.map(line => [lineKey(line), line.qty])) }
    return prepared
  })
  if (operation) channel?.postMessage(key)
  void refreshCart(key)
  return operation
}

export async function finishCheckout(operation: PreparedCheckout, submitted: boolean) {
  await withCart(operation.key, record => {
    if (record.ended || record.checkout?.operation.payload.idempotencyKey !== operation.payload.idempotencyKey) return
    if (submitted) {
      const { reserved } = record.checkout
      record.draft.items = record.draft.items.map(line => ({
        ...line, qty: line.qty - (reserved[lineKey(line)] ?? 0)
      })).filter(line => line.qty > 0)
      if ((record.draft.commentDraft?.trim() || null) === operation.payload.comment) delete record.draft.commentDraft
      if (same(record.draft.giftDecisionDraft?.decision, operation.payload.giftDecision)) delete record.draft.giftDecisionDraft
    }
    delete record.checkout
    record.revision = (record.revision ?? 0) + 1
  })
  channel?.postMessage(operation.key)
  await refreshCart(operation.key)
}

export async function cartRequiresTabChoice(tableToken: string, sessionId: number, generation?: string): Promise<boolean> {
  const key = getDraftStorageKey(tableToken, sessionId, getCurrentUserPart(), generation)
  try { return await withCart(key, record => record.draft.items.length > 0) } catch { return true }
}

export function discardTableCart(store: IDBObjectStore, owner: string, tableToken: string, sessionId: number, generation?: string) {
  const key = getDraftStorageKey(tableToken, sessionId, owner, generation)
  store.put({ ended: true, draft: emptyDraft() }, key)
  store.transaction.addEventListener('complete', () => {
    removeMigratedDraft(key)
    editors.delete(key)
    if (activeDraftStorageKey === key) setCartTableToken(null, null)
    channel?.postMessage(key)
  }, { once: true })
}

export function setCartTableToken(tableToken: string | null, sessionId: number | null, generation?: string): void {
  const userPart = getCurrentUserPart()
  clearPersistedGiftDecisionsForAccountSwitch(userPart)
  const key = tableToken && sessionId && userPart !== 'user:unknown' ? getDraftStorageKey(tableToken, sessionId, userPart, generation) : null
  if (key === activeDraftStorageKey) return
  activeDraftStorageKey = key
  clearGiftDecisionDraft()
  clearMenuSelectionRecoveryState()
  items = new Map()
  totalQty = 0
  commentDraft = ''
  draftStorage = key ? 'unread' : 'ready'
  if (!key) { notify(); return }
  const editor = editors.get(key) ?? { key, committedQty: 0, local: emptyDraft(), edits: [], status: 'unread', busy: false }
  editors.set(key, editor)
  if (editor.edits.length) display(editor, true)
  else {
    editor.status = 'unread'
    display(editor)
    void synchronize(editor)
  }
}

export function retryCartStorage(): boolean {
  const editor = activeDraftStorageKey && editors.get(activeDraftStorageKey)
  if (editor && !editor.conflict) void synchronize(editor)
  return draftStorage === 'ready'
}

export function resolveCartConflict(useMine: boolean) {
  const editor = activeDraftStorageKey && editors.get(activeDraftStorageKey)
  if (!editor || !editor.conflict) return
  const observed = editor.conflict
  editor.conflict = undefined
  void synchronize(editor, { observed, useMine })
}

function canEdit() { return draftStorage === 'ready' || draftStorage === 'saving' }

if (channel) channel.onmessage = event => {
  const editor = editors.get(event.data)
  if (editor && editor.key === activeDraftStorageKey && !editor.conflict) {
    if (editor.busy) editor.refresh = true
    else if (!editor.edits.length) void synchronize(editor)
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

export function addToCart(itemId: number, option?: CartLineOptionInput | null): SetQtyResult {
  if (!canEdit()) return { ok: false, reason: 'storage' }
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  const key = buildCartLineKey(itemId, option?.selectedOptionId, option?.preferenceNote)
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
  clearGiftDecisionDraft()
  updateTotals()
  persistActiveDraft(true)
  notify()
  return { ok: true }
}

export function addLinesToCart(additions: CartLineAddition[]): SetQtyResult {
  if (!canEdit()) return { ok: false, reason: 'storage' }
  const nextItems = new Map<string, CartLine>(
    Array.from(items.entries()).map(([key, line]): [string, CartLine] => [key, { ...line }])
  )
  for (const addition of additions) {
    if (!isValidItemId(addition.itemId)) {
      return { ok: false, reason: 'invalid' }
    }
    const quantity = normalizeQty(addition.qty)
    if (quantity <= 0 || quantity !== addition.qty) {
      return { ok: false, reason: 'invalid' }
    }
    const key = buildCartLineKey(addition.itemId, addition.selectedOptionId, addition.preferenceNote)
    const currentLine = nextItems.get(key)
    if (!currentLine && nextItems.size >= MAX_DISTINCT) {
      return { ok: false, reason: 'limit' }
    }
    const currentQty = normalizeQty(currentLine?.qty ?? 0)
    const nextQty = currentQty + quantity
    if (nextQty > MAX_QTY) {
      return { ok: false, reason: 'limit' }
    }
    nextItems.set(key, buildLine(addition.itemId, nextQty, addition, currentLine))
  }
  items = nextItems
  clearGiftDecisionDraft()
  updateTotals()
  persistActiveDraft(true)
  notify()
  return { ok: true }
}

export function removeFromCart(itemId: number, selectedOptionId?: number | null, preferenceNote?: string | null): void {
  if (!isValidItemId(itemId)) {
    return
  }
  removeCartLine(buildCartLineKey(itemId, selectedOptionId, preferenceNote))
}

export function removeCartLine(lineKey: string): void {
  if (!canEdit()) return
  const currentLine = items.get(lineKey)
  if (!currentLine) {
    return
  }
  const nextQty = normalizeQty(currentLine.qty - 1)
  if (nextQty === 0) {
    items.delete(lineKey)
    menuSelectionIssues.delete(lineKey)
    pendingValidationLineRefs.delete(lineKey)
  } else {
    items.set(lineKey, { ...currentLine, qty: nextQty })
  }
  clearGiftDecisionDraft()
  updateTotals()
  persistActiveDraft(true)
  notify()
}

export function setCartQty(
  itemId: number,
  qty: number,
  selectedOptionId?: number | null,
  preferenceNote?: string | null
): SetQtyResult {
  if (!isValidItemId(itemId)) {
    return { ok: false, reason: 'invalid' }
  }
  return setCartLineQty(buildCartLineKey(itemId, selectedOptionId, preferenceNote), qty, itemId, selectedOptionId)
}

export function setCartLineQty(
  lineKey: string,
  qty: number,
  fallbackItemId?: number,
  fallbackSelectedOptionId?: number | null
): SetQtyResult {
  if (!canEdit()) return { ok: false, reason: 'storage' }
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
    menuSelectionIssues.delete(lineKey)
    pendingValidationLineRefs.delete(lineKey)
  } else {
    items.set(
      lineKey,
      buildLine(
        itemId,
        nextQty,
        {
          selectedOptionId: currentLine?.selectedOptionId ?? fallbackSelectedOptionId ?? null,
          selectedOptionName: currentLine?.selectedOptionName ?? null,
          priceDeltaMinor: currentLine?.priceDeltaMinor ?? null,
          preferenceNote: currentLine?.preferenceNote ?? null
        },
        currentLine
      )
    )
  }
  clearGiftDecisionDraft()
  updateTotals()
  persistActiveDraft()
  notify()
  return { ok: true }
}

export function replaceCartLineOption(
  lineKey: string,
  option: Pick<MenuItemOptionDto, 'id' | 'name' | 'priceDeltaMinor'>,
  preferenceNote?: string | null
): { ok: true; lineKey: string } | { ok: false; reason: 'missing' | 'invalid' | 'conflict' } {
  if (!canEdit()) return { ok: false, reason: 'missing' }
  const currentLine = items.get(lineKey)
  if (!currentLine) {
    return { ok: false, reason: 'missing' }
  }
  if (!Number.isSafeInteger(option.id) || option.id <= 0 || !option.name.trim()) {
    return { ok: false, reason: 'invalid' }
  }
  const nextPreferenceNote = normalizePreferenceNote(preferenceNote ?? currentLine.preferenceNote)
  const nextKey = buildCartLineKey(currentLine.itemId, option.id, nextPreferenceNote)
  if (nextKey !== lineKey && items.has(nextKey)) {
    return { ok: false, reason: 'conflict' }
  }
  const nextLine = buildLine(
    currentLine.itemId,
    currentLine.qty,
    {
      selectedOptionId: option.id,
      selectedOptionName: option.name,
      priceDeltaMinor: option.priceDeltaMinor,
      preferenceNote: nextPreferenceNote
    },
    currentLine
  )
  items.delete(lineKey)
  menuSelectionIssues.delete(lineKey)
  pendingValidationLineRefs.delete(lineKey)
  items.set(nextKey, nextLine)
  pendingValidationLineRefs.add(nextKey)
  clearGiftDecisionDraft()
  updateTotals()
  persistActiveDraft(false, nextKey === lineKey ? undefined : { from: lineKey, to: nextKey })
  notify()
  return { ok: true, lineKey: nextKey }
}

export function setCartMenuSelectionIssues(issues: CartMenuSelectionIssue[]): void {
  menuSelectionIssues = new Map(issues.map((issue) => [issue.cartLineRef, { ...issue }]))
  pendingValidationLineRefs = new Set()
  notify()
}

export function clearCartMenuSelectionIssues(): void {
  if (!menuSelectionIssues.size && !pendingValidationLineRefs.size) {
    return
  }
  clearMenuSelectionRecoveryState()
  notify()
}

export function setCartCommentDraft(value: string): void {
  if (!canEdit()) return
  if (commentDraft.trim() !== value.trim()) {
    clearGiftDecisionDraft()
  }
  commentDraft = value
  persistActiveDraft()
  notify()
}

export function setCartGiftDecision(
  value: GiftDecisionDto | null,
  scope?: CartGiftDecisionScope | null
): void {
  if (!canEdit()) return
  if (!value) {
    clearGiftDecisionDraft()
    persistActiveDraft()
    notify()
    return
  }
  const normalizedDecision = normalizeGiftDecision(value)
  const normalizedDraft = normalizeScopedGiftDecisionDraft(
    normalizedDecision && scope
      ? {
          ...scope,
          decision: normalizedDecision
        }
      : null
  )
  if (!normalizedDraft || normalizedDraft.expiresAtEpochSeconds <= Math.floor(Date.now() / 1000)) {
    clearGiftDecisionDraft()
  } else {
    giftDecision = normalizedDraft.decision
    giftDecisionDraft = normalizedDraft
    pendingGiftDecisionDraft = null
  }
  persistActiveDraft()
  notify()
}

function giftDecisionScopeMatches(
  draft: ScopedGiftDecisionDraft,
  context: CartGiftDecisionContext
): boolean {
  return (
    draft.userId === context.userId &&
    draft.venueId === context.venueId &&
    draft.tableSessionId === context.tableSessionId &&
    draft.tabId === context.tabId &&
    draft.cartFingerprint === context.cartFingerprint
  )
}

export function clearMismatchedCartGiftDecisionOwner(
  context: CartGiftDecisionOwnerContext
): boolean {
  const draft = giftDecisionDraft ?? pendingGiftDecisionDraft
  if (
    !draft ||
    (draft.userId === context.userId &&
      draft.venueId === context.venueId &&
      draft.tableSessionId === context.tableSessionId &&
      draft.tabId === context.tabId)
  ) {
    return false
  }
  const hadActiveDecision = giftDecision != null
  clearGiftDecisionDraft()
  persistActiveDraft()
  if (hadActiveDecision) {
    notify()
  }
  return true
}

export function reconcileCartGiftDecisionScope(
  context: CartGiftDecisionContext,
  nowEpochSeconds = Math.floor(Date.now() / 1000)
): CartGiftDecisionReconcileResult {
  const draft = giftDecisionDraft ?? pendingGiftDecisionDraft
  if (!draft) {
    return 'none'
  }
  if (draft.expiresAtEpochSeconds <= nowEpochSeconds || !giftDecisionScopeMatches(draft, context)) {
    const hadActiveDecision = giftDecision != null
    clearGiftDecisionDraft()
    persistActiveDraft()
    if (hadActiveDecision) {
      notify()
    }
    return 'cleared'
  }
  if (giftDecisionDraft) {
    return 'active'
  }
  giftDecision = draft.decision
  giftDecisionDraft = draft
  pendingGiftDecisionDraft = null
  persistActiveDraft()
  notify()
  return 'restored'
}

export function clearExpiredCartGiftDecision(
  nowEpochSeconds = Math.floor(Date.now() / 1000)
): boolean {
  const draft = giftDecisionDraft ?? pendingGiftDecisionDraft
  if (!draft || draft.expiresAtEpochSeconds > nowEpochSeconds) {
    return false
  }
  const hadActiveDecision = giftDecision != null
  clearGiftDecisionDraft()
  persistActiveDraft()
  if (hadActiveDecision) {
    notify()
  }
  return true
}

export function clearCart(): void {
  if (!canEdit()) return
  items = new Map()
  totalQty = 0
  commentDraft = ''
  clearGiftDecisionDraft()
  clearMenuSelectionRecoveryState()
  persistActiveDraft()
  notify()
}
