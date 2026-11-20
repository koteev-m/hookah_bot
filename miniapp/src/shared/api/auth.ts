import { resolveRequestId } from './errorMapping'
import {
  ApiErrorCodes,
  REQUEST_ID_HEADER,
  type ApiErrorEnvelope,
  type ApiErrorInfo,
  type ApiResult
} from './types'
import { getBackendBaseUrl } from './backend'
import { bindSessionActor, sessionStorageKey } from './sessionBinding'
import { isDebugEnabled } from '../debug'
import { normalizeBotUsername } from '../validation/tableToken'
import { getTelegramContext } from '../telegram'

export type SessionToken = {
  userId?: number
  token: string
  botUsername?: string | null
  expiresAtEpochSeconds: number
}

type TelegramAuthResponse = {
  token: string
  botUsername?: string | null
  expiresAtEpochSeconds: number
  user?: {
    telegramUserId?: number
  }
}

const isDebug = isDebugEnabled()
const sessionSafetySkewSeconds = 20
let inMemorySession: SessionToken | null = null
let inMemorySessionUserId: number | null = null
let pendingAuth: Promise<ApiResult<{ token: string }>> | null = null

function nowEpochSeconds() {
  return Math.floor(Date.now() / 1000)
}

function isSessionValid(session: SessionToken) {
  return nowEpochSeconds() < session.expiresAtEpochSeconds - sessionSafetySkewSeconds
}

function getCurrentTelegramUserId(): number | null {
  return getTelegramContext().telegramUserId
}

function isInMemorySessionForCurrentUser(): boolean {
  return inMemorySessionUserId === getCurrentTelegramUserId()
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
  const storageKey = sessionStorageKey(getCurrentTelegramUserId())
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
  inMemorySessionUserId = getCurrentTelegramUserId()
  safeSetItem(sessionStorageKey(inMemorySessionUserId), JSON.stringify(session))
  bindSessionActor(session.userId ?? null)
}

export function getStoredSession(): SessionToken | null {
  if (inMemorySession && isInMemorySessionForCurrentUser() && isSessionValid(inMemorySession)) {
    return inMemorySession
  }
  const storedSession = loadStoredSession()
  if (storedSession && isSessionValid(storedSession)) {
    inMemorySession = storedSession
    inMemorySessionUserId = getCurrentTelegramUserId()
    return storedSession
  }
  return null
}

export function storeSession(session: SessionToken): void {
  persistSession(session)
}

export function clearSession(): void {
  const key = sessionStorageKey(getCurrentTelegramUserId())
  inMemorySession = null
  inMemorySessionUserId = null
  safeRemoveItem(key)
  bindSessionActor(null)
}

export function getSessionBotUsername(): string | null {
  return normalizeBotUsername(getStoredSession()?.botUsername)
}

export async function authenticate(initData: string): Promise<SessionToken> {
  const backendUrl = getBackendBaseUrl()
  try {
    const response = await fetch(`${backendUrl}/api/auth/telegram`, {
      method: 'POST',
      redirect: 'error',
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
      userId: data.user?.telegramUserId,
      token: data.token,
      botUsername: normalizeBotUsername(data.botUsername),
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
  const launch = getTelegramContext()
  const storedSession = loadStoredSession()
  const rebindLegacy = Boolean(launch.initData) && !!storedSession && (!storedSession.userId || storedSession.userId !== launch.telegramUserId)
  if (!rebindLegacy && inMemorySession && isInMemorySessionForCurrentUser() && isSessionValid(inMemorySession)) {
    return { ok: true, data: { token: inMemorySession.token } }
  }

  if (!rebindLegacy && storedSession) {
    inMemorySession = storedSession
    inMemorySessionUserId = getCurrentTelegramUserId()
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
      if (!session.token || !isSessionValid(session) || (rebindLegacy && !session.userId) || getTelegramContext().initData !== initData ||
          (session.userId != null && session.userId !== getCurrentTelegramUserId())) {
        return { ok: false, error: { status: 401, code: ApiErrorCodes.UNAUTHORIZED, message: 'Аккаунт изменился. Откройте Mini App заново.' } }
      }
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
