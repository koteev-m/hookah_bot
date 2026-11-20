import type { GuestTabDto } from '../api/guestDtos'
import { getTelegramContext } from '../telegram'

const STORAGE_KEY = 'hookahMiniAppGuestTabSelection'

type TabSelection = number | 'choose'
let memorySelection: Record<string, TabSelection> = {}

function normalizeId(value: number | null | undefined): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
    return null
  }
  return value
}

function resolveUserStoragePart(): string {
  const userId = getTelegramContext().telegramUserId
  return userId ? `user:${userId}` : 'user:unknown'
}

function resolveSelectionKey(tableSessionId: number): string {
  return `${resolveUserStoragePart()}:session:${tableSessionId}`
}

function readStorage(): Record<string, TabSelection> {
  if (typeof window === 'undefined') {
    return memorySelection
  }
  try {
    const raw = window.sessionStorage.getItem(STORAGE_KEY)
    if (!raw) {
      return memorySelection
    }
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') {
      return memorySelection
    }
    const next: Record<string, TabSelection> = {}
    Object.entries(parsed).forEach(([key, value]) => {
      const tabId = typeof value === 'number' ? value : Number(value)
      const normalizedTabId = normalizeId(tabId)
      if (!normalizedTabId && value !== 'choose') {
        return
      }
      if (normalizeId(Number(key)) || /^user:(?:\d+|unknown):session:\d+$/.test(key)) {
        next[key] = normalizedTabId ?? 'choose'
      }
    })
    memorySelection = next
    return next
  } catch {
    return memorySelection
  }
}

function writeStorage(selection: Record<string, TabSelection>): void {
  memorySelection = selection
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(selection))
  } catch {
    // Session storage is a convenience only; in-memory state still keeps navigation scoped.
  }
}

export function getSelectedGuestTabId(tableSessionId: number | null | undefined): number | null {
  const sessionId = normalizeId(tableSessionId)
  if (!sessionId) {
    return null
  }
  const value = readStorage()[resolveSelectionKey(sessionId)]
  return typeof value === 'number' ? normalizeId(value) : null
}

export function setSelectedGuestTabId(
  tableSessionId: number | null | undefined,
  tabId: number | null | undefined
): void {
  const sessionId = normalizeId(tableSessionId)
  if (!sessionId) {
    return
  }
  const selection = { ...readStorage() }
  const normalizedTabId = normalizeId(tabId)
  const selectionKey = resolveSelectionKey(sessionId)
  if (!normalizedTabId) {
    delete selection[selectionKey]
  } else {
    selection[selectionKey] = normalizedTabId
  }
  writeStorage(selection)
}

export function restoreGuestTabSelection(sessionId: number, defaultId: number | null, hasDraft: boolean): void {
  const selection = readStorage()
  const key = resolveSelectionKey(sessionId)
  if (selection[key] !== undefined) return
  if (hasDraft) writeStorage({ ...selection, [key]: 'choose' })
  else if (defaultId !== null) writeStorage({ ...selection, [key]: defaultId })
}

export function chooseGuestTab(tabs: GuestTabDto[], tableSessionId: number | null): GuestTabDto | null {
  const active = tabs.filter(tab => tab.status === 'ACTIVE' && tab.tableSessionId === tableSessionId)
  const selected = tableSessionId ? readStorage()[resolveSelectionKey(tableSessionId)] : undefined
  if (selected === 'choose') return null
  if (selected !== undefined) return active.find(tab => tab.id === selected) ?? null
  return active.find(tab => tab.type === 'PERSONAL') ?? active[0] ?? null
}
