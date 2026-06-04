const STORAGE_KEY = 'hookahMiniAppGuestTabSelection'

let memorySelection: Record<string, number> = {}

function normalizeId(value: number | null | undefined): number | null {
  if (typeof value !== 'number' || !Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
    return null
  }
  return value
}

function readStorage(): Record<string, number> {
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
    const next: Record<string, number> = {}
    Object.entries(parsed).forEach(([key, value]) => {
      const sessionId = Number(key)
      const tabId = typeof value === 'number' ? value : Number(value)
      if (normalizeId(sessionId) && normalizeId(tabId)) {
        next[String(sessionId)] = tabId
      }
    })
    memorySelection = next
    return next
  } catch {
    return memorySelection
  }
}

function writeStorage(selection: Record<string, number>): void {
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
  const value = readStorage()[String(sessionId)]
  return normalizeId(value)
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
  if (!normalizedTabId) {
    delete selection[String(sessionId)]
  } else {
    selection[String(sessionId)] = normalizedTabId
  }
  writeStorage(selection)
}
