import { getBackendBaseUrl } from './backend'

const actorKey = 'hookah_authenticated_session_actor'

export function sessionStorageKey(userId: number | null): string {
  const user = userId ? `user:${userId}` : 'user:unknown'
  try {
    return `hookah_session_token:${new URL(getBackendBaseUrl()).host}:${user}`
  } catch {
    return `hookah_session_token:${user}`
  }
}

export function bindSessionActor(userId: number | null): void {
  try {
    const previous = Number(sessionStorage.getItem(actorKey))
    if (Number.isSafeInteger(previous) && previous > 0 && previous !== userId) {
      sessionStorage.removeItem(sessionStorageKey(previous))
    }
    if (userId === null) sessionStorage.removeItem(actorKey)
    else sessionStorage.setItem(actorKey, String(userId))
  } catch { /* Storage can be unavailable in a WebView. */ }
}

export function boundSessionActor(): number | null {
  try {
    const id = Number(sessionStorage.getItem(actorKey))
    if (!Number.isSafeInteger(id) || id <= 0) return null
    const raw = sessionStorage.getItem(sessionStorageKey(id))
    if (!raw) return null
    const session = JSON.parse(raw)
    return session.userId === id && typeof session.token === 'string' && session.token &&
      Number.isFinite(session.expiresAtEpochSeconds) && Date.now() / 1000 + 20 < session.expiresAtEpochSeconds ? id : null
  } catch {
    return null
  }
}
