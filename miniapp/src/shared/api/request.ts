import { isGuestApi, resolveRequestId } from './errorMapping'
import {
  ApiErrorCodes,
  REQUEST_ID_HEADER,
  type ApiErrorEnvelope,
  type ApiErrorInfo,
  type ApiResult
} from './types'
import { type clearSession, type ensureGuestSession } from '../session/guestSession'

export type RequestDependencies = {
  isDebug: boolean
  ensureGuestSession: typeof ensureGuestSession
  clearSession: typeof clearSession
}

export async function requestApi<T>(
  backendUrl: string,
  path: string,
  init: RequestInit | undefined,
  deps: RequestDependencies
): Promise<ApiResult<T>> {
  try {
    if (isGuestApi(path)) {
      const sessionResult = await deps.ensureGuestSession(backendUrl, deps.isDebug)
      if (!sessionResult.ok) {
        return { ok: false, error: sessionResult.error }
      }
      const headers = new Headers(init?.headers ?? {})
      headers.set('Authorization', `Bearer ${sessionResult.data.token}`)
      init = { ...init, headers }
    }

    try {
      const response = await fetch(`${backendUrl}${path}`, init)
      if (response.ok) {
        return { ok: true, data: (await response.json()) as T }
      }

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
      if (isGuestApi(path) && response.status === 401) {
        deps.clearSession(backendUrl, deps.isDebug)
      }
      return { ok: false, error: errorInfo }
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
  } catch (error) {
    return {
      ok: false,
      error: {
        status: 0,
        code: ApiErrorCodes.INTERNAL_ERROR,
        message: 'Ошибка приложения. Попробуйте перезапустить.'
      }
    }
  }
}
