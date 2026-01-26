import { resolveRequestId } from './errorMapping'
import {
  ApiErrorCodes,
  REQUEST_ID_HEADER,
  type ApiErrorEnvelope,
  type ApiErrorInfo,
  type ApiResult
} from './types'
import { getBackendBaseUrl } from './backend'
import { isDebugEnabled } from '../debug'
import { getTelegramContext } from '../telegram'

export type SessionToken = {
  token: string
  expiresAtEpochSeconds: number
}

type TelegramAuthResponse = {
  token: string
  expiresAtEpochSeconds: number
  user?: {
    telegramUserId?: number
  }
}

const baseStorageKey = 'hookah_session_token'
const isDebug = isDebugEnabled()
const storageKey = resolveStorageKey()
const sessionSafetySkewSeconds = 20
let inMemorySession: SessionToken | null = null
let pendingAuth: Promise<ApiResult<{ token: string }>> | null = null

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000)
}

function isSessionValid(session: SessionToken) {
  return nowEpochSeconds() < session.expiresAtEpochSeconds - sessionSafetySkewSeconds
}

function safeGetItem(key: string): string | null {
  try {
    return sessionStorage.getItem(key)
  } catch (error) {
    return null
  }
}

function safeSetItem(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value)
  } catch (error) {
    // ignore storage errors
  }
}

function safeRemoveItem(key: string) {
  try {
    sessionStorage.removeItem(key)
  } catch (error) {
    // ignore storage errors
  }
}

function parseStoredSession(raw: string): SessionToken | null {
  try {
    const parsed = JSON.parse(raw) as SessionToken
    if (!parsed.token || !parsed.expiresAtEpochSeconds) {
      return null
    }
    return parsed
  } catch (error) {
    return null
  }
}

function loadStoredSession(): SessionToken | null {
  const raw = safeGetItem(storageKey)
  if (!raw) {
    return null
  }
  const parsed = parseStoredSession(raw)
  if (!parsed) {
    safeRemoveItem(storageKey)
    return null
  }
  if (!isSessionValid(parsed)) {
    safeRemoveItem(storageKey)
    return null
  }
  return parsed
}

function persistSession(session: SessionToken) {
  inMemorySession = session
  safeSetItem(storageKey, JSON.stringify(session))
}

export function getStoredSession(): SessionToken | null {
  if (inMemorySession && isSessionValid(inMemorySession)) {
    return inMemorySession
  }
  const storedSession = loadStoredSession()
  if (storedSession && isSessionValid(storedSession)) {
    inMemorySession = storedSession
    return storedSession
  }
  return null
}

export function storeSession(session: SessionToken): void {
  persistSession(session)
}

export function clearSession(): void {
  inMemorySession = null
  safeRemoveItem(storageKey)
}

export async function authenticate(initData: string): Promise<SessionToken> {
  const backendUrl = getBackendBaseUrl()
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
      const requestId = resolveRequestId(headerRequestId, envelope?.requestId, isDebug)
      const errorInfo: ApiErrorInfo = {
        status: response.status,
        code: envelope?.error?.code,
        message: envelope?.error?.message,
        requestId
      }
      throw new AuthError(errorInfo)
    }

    const data = (await response.json()) as TelegramAuthResponse
    return {
      token: data.token,
      expiresAtEpochSeconds: data.expiresAtEpochSeconds
    }
  } catch (error) {
    if (error instanceof AuthError) {
      throw error
    }
    throw new AuthError({
      status: 0,
      code: ApiErrorCodes.NETWORK_ERROR,
      message: 'Нет соединения / ошибка сети'
    })
  }
}

class AuthError extends Error {
  info: ApiErrorInfo

  constructor(info: ApiErrorInfo) {
    super(info.message ?? 'Ошибка авторизации')
    this.info = info
  }
}

export async function getAccessToken(): Promise<ApiResult<{ token: string }>> {
  if (inMemorySession && isSessionValid(inMemorySession)) {
    return { ok: true, data: { token: inMemorySession.token } }
  }

  const storedSession = loadStoredSession()
  if (storedSession && isSessionValid(storedSession)) {
    inMemorySession = storedSession
    return { ok: true, data: { token: storedSession.token } }
  }

  clearSession()

  const initData = getTelegramContext().initData
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

  if (pendingAuth) {
    try {
      return await pendingAuth
    } finally {
      pendingAuth = null
    }
  }

  pendingAuth = (async () => {
    try {
      const session = await authenticate(initData)
      persistSession(session)
      return { ok: true, data: { token: session.token } }
    } catch (error) {
      if (error instanceof AuthError) {
        return { ok: false, error: error.info }
      }
      return {
        ok: false,
        error: {
          status: 0,
          code: ApiErrorCodes.INTERNAL_ERROR,
          message: 'Ошибка приложения. Попробуйте перезапустить.'
        }
      }
    }
  })()

  try {
    return await pendingAuth
  } finally {
    pendingAuth = null
  }
}
function resolveStorageKey(): string {
  try {
    const backendUrl = getBackendBaseUrl()
    const parsed = new URL(backendUrl)
    if (!parsed.host) {
      return baseStorageKey
    }
    return `${baseStorageKey}:${parsed.host}`
  } catch (error) {
    return baseStorageKey
  }
}
