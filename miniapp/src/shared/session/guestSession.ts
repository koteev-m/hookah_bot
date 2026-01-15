import {
  ApiErrorCodes,
  type ApiErrorEnvelope,
  type ApiErrorInfo,
  type ApiResult
} from '../api/types'
import { resolveRequestId } from '../api/errorMapping'
import { getTelegramInitData } from '../telegram'
import { REQUEST_ID_HEADER } from '../api/types'

export type GuestSession = {
  token: string
  expiresAtEpochSeconds: number
  telegramUserId?: number
}

export type TelegramAuthResponse = {
  token: string
  expiresAtEpochSeconds: number
  user: {
    telegramUserId: number
  }
}

const guestSessionStorageKeyPrefix = 'hookah_guest_session'
const sessionSafetySkewSeconds = 15
let inMemorySession: GuestSession | null = null
let pendingAuth: Promise<ApiResult<{ token: string }>> | null = null

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000)
}

function isSessionValid(session: GuestSession) {
  return nowEpochSeconds() < session.expiresAtEpochSeconds - sessionSafetySkewSeconds
}

function buildGuestSessionStorageKey(url: string) {
  try {
    const parsedUrl = new URL(url)
    return `${guestSessionStorageKeyPrefix}:${parsedUrl.host}`
  } catch (error) {
    return `${guestSessionStorageKeyPrefix}:${url}`
  }
}

function safeGetItem(key: string, isDebug: boolean): string | null {
  try {
    return localStorage.getItem(key)
  } catch (error) {
    if (isDebug) {
      console.warn('Failed to read localStorage', error)
    }
    return null
  }
}

function safeSetItem(key: string, value: string, isDebug: boolean) {
  try {
    localStorage.setItem(key, value)
  } catch (error) {
    if (isDebug) {
      console.warn('Failed to write localStorage', error)
    }
  }
}

function safeRemoveItem(key: string, isDebug: boolean) {
  try {
    localStorage.removeItem(key)
  } catch (error) {
    if (isDebug) {
      console.warn('Failed to remove localStorage key', error)
    }
  }
}

function parseStoredSession(raw: string): GuestSession | null {
  try {
    const parsed = JSON.parse(raw) as GuestSession
    if (!parsed.token || !parsed.expiresAtEpochSeconds) {
      return null
    }
    return parsed
  } catch (error) {
    return null
  }
}

function loadStoredSession(backendUrl: string, isDebug: boolean): GuestSession | null {
  const storageKey = buildGuestSessionStorageKey(backendUrl)
  const raw = safeGetItem(storageKey, isDebug)
  if (raw) {
    return parseStoredSession(raw)
  }

  const legacyRaw = safeGetItem(guestSessionStorageKeyPrefix, isDebug)
  if (!legacyRaw) {
    return null
  }
  const legacySession = parseStoredSession(legacyRaw)
  if (!legacySession) {
    return null
  }
  safeSetItem(storageKey, JSON.stringify(legacySession), isDebug)
  safeRemoveItem(guestSessionStorageKeyPrefix, isDebug)
  return legacySession
}

function persistSession(session: GuestSession, backendUrl: string, isDebug: boolean) {
  inMemorySession = session
  const storageKey = buildGuestSessionStorageKey(backendUrl)
  safeSetItem(storageKey, JSON.stringify(session), isDebug)
}

export function clearSession(backendUrl: string, isDebug: boolean) {
  inMemorySession = null
  const storageKey = buildGuestSessionStorageKey(backendUrl)
  safeRemoveItem(storageKey, isDebug)
}

async function authorizeGuest(backendUrl: string, isDebug: boolean): Promise<ApiResult<{ token: string }>> {
  const initData = getTelegramInitData()
  if (!initData) {
    return {
      ok: false,
      error: {
        status: 401,
        code: ApiErrorCodes.INITDATA_INVALID,
        message: 'Откройте мини-приложение из Telegram.'
      }
    }
  }

  try {
    const response = await fetch(`${backendUrl}/api/auth/telegram`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ initData })
    })

    if (!response.ok) {
      const headerRequestId = response.headers.get(REQUEST_ID_HEADER)
      let envelope: ApiErrorEnvelope | null = null
      try {
        envelope = (await response.json()) as ApiErrorEnvelope
      } catch (error) {
        envelope = null
      }
      const requestId = resolveRequestId(headerRequestId, envelope?.requestId)
      const errorInfo: ApiErrorInfo = {
        status: response.status,
        code: envelope?.error?.code,
        message: envelope?.error?.message,
        requestId
      }
      return { ok: false, error: errorInfo }
    }

    const data = (await response.json()) as TelegramAuthResponse
    const session: GuestSession = {
      token: data.token,
      expiresAtEpochSeconds: data.expiresAtEpochSeconds,
      telegramUserId: data.user?.telegramUserId
    }
    persistSession(session, backendUrl, isDebug)
    return { ok: true, data: { token: session.token } }
  } catch (error) {
    return {
      ok: false,
      error: {
        status: 0,
        code: ApiErrorCodes.NETWORK_ERROR,
        message: 'Нет соединения / ошибка сети'
      }
    }
  }
}

export async function ensureGuestSession(
  backendUrl: string,
  isDebug: boolean
): Promise<ApiResult<{ token: string }>> {
  if (inMemorySession && isSessionValid(inMemorySession)) {
    return { ok: true, data: { token: inMemorySession.token } }
  }

  const storedSession = loadStoredSession(backendUrl, isDebug)
  if (storedSession && isSessionValid(storedSession)) {
    inMemorySession = storedSession
    return { ok: true, data: { token: storedSession.token } }
  }

  clearSession(backendUrl, isDebug)

  if (pendingAuth) {
    try {
      return await pendingAuth
    } finally {
      pendingAuth = null
    }
  }

  pendingAuth = authorizeGuest(backendUrl, isDebug)

  try {
    return await pendingAuth
  } finally {
    pendingAuth = null
  }
}
