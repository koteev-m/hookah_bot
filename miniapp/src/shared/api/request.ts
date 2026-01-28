import { isAbortError, REQUEST_ABORTED_CODE } from './abort'
import { isAuthenticatedApi, resolveRequestId } from './errorMapping'
import {
  ApiErrorCodes,
  REQUEST_ID_HEADER,
  type ApiErrorEnvelope,
  type ApiErrorInfo,
  type ApiResult
} from './types'
import { type clearSession, type getAccessToken } from './auth'

export type RequestDependencies = {
  isDebug: boolean
  getAccessToken: typeof getAccessToken
  clearSession: typeof clearSession
}

export type BinaryResponse = {
  blob: Blob
  contentType: string | null
  fileName: string | null
}

export async function requestApi<T>(
  backendUrl: string,
  path: string,
  init: RequestInit | undefined,
  deps: RequestDependencies
): Promise<ApiResult<T>> {
  try {
    if (isAuthenticatedApi(path)) {
      const sessionResult = await deps.getAccessToken()
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
      const requestId = resolveRequestId(headerRequestId, envelope?.requestId, deps.isDebug)
      const errorInfo: ApiErrorInfo = {
        status: response.status,
        code: envelope?.error?.code,
        message: envelope?.error?.message,
        requestId
      }
      if (isAuthenticatedApi(path) && response.status === 401) {
        deps.clearSession()
      }
      return { ok: false, error: errorInfo }
    } catch (error) {
      if (isAbortError(error)) {
        // Use a dedicated code so screens can ignore aborted requests without UI noise.
        return {
          ok: false,
          error: {
            status: 0,
            code: REQUEST_ABORTED_CODE,
            message: ''
          }
        }
      }
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

export async function requestBinary(
  backendUrl: string,
  path: string,
  init: RequestInit | undefined,
  deps: RequestDependencies
): Promise<ApiResult<BinaryResponse>> {
  try {
    if (isAuthenticatedApi(path)) {
      const sessionResult = await deps.getAccessToken()
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
        const blob = await response.blob()
        const contentType = response.headers.get('Content-Type')
        const contentDisposition = response.headers.get('Content-Disposition')
        const fileName = parseContentDispositionFileName(contentDisposition)
        return { ok: true, data: { blob, contentType, fileName } }
      }

      const headerRequestId = response.headers.get(REQUEST_ID_HEADER)
      let envelope: ApiErrorEnvelope | null = null
      try {
        envelope = (await response.json()) as ApiErrorEnvelope
      } catch (error) {
        envelope = null
      }
      const requestId = resolveRequestId(headerRequestId, envelope?.requestId, deps.isDebug)
      const errorInfo: ApiErrorInfo = {
        status: response.status,
        code: envelope?.error?.code,
        message: envelope?.error?.message,
        requestId
      }
      if (isAuthenticatedApi(path) && response.status === 401) {
        deps.clearSession()
      }
      return { ok: false, error: errorInfo }
    } catch (error) {
      if (isAbortError(error)) {
        return {
          ok: false,
          error: {
            status: 0,
            code: REQUEST_ABORTED_CODE,
            message: ''
          }
        }
      }
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

function parseContentDispositionFileName(value: string | null): string | null {
  if (!value) {
    return null
  }
  const utfMatch = value.match(/filename\\*=UTF-8''([^;]+)/i)
  if (utfMatch?.[1]) {
    try {
      return decodeURIComponent(utfMatch[1])
    } catch (error) {
      return utfMatch[1]
    }
  }
  const asciiMatch = value.match(/filename=\"?([^\";]+)\"?/i)
  return asciiMatch?.[1] ?? null
}
