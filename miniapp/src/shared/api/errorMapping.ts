import { ApiErrorCodes, type ApiErrorCode, type ApiErrorInfo } from './types'

export function resolveRequestId(
  headerValue: string | null,
  bodyValue: string | null | undefined,
  isDebug: boolean
): string | undefined {
  if (isDebug && headerValue && bodyValue && headerValue !== bodyValue) {
    console.warn('RequestId mismatch between header and body', { headerValue, bodyValue })
  }
  return headerValue ?? bodyValue ?? undefined
}

export function normalizeErrorCode(error: ApiErrorInfo): ApiErrorCode | undefined {
  if (error.code && (Object.values(ApiErrorCodes) as string[]).includes(error.code)) {
    return error.code as ApiErrorCode
  }
  switch (error.status) {
    case 400:
      return ApiErrorCodes.INVALID_INPUT
    case 401:
      return ApiErrorCodes.UNAUTHORIZED
    case 403:
      return ApiErrorCodes.FORBIDDEN
    case 404:
      return ApiErrorCodes.NOT_FOUND
    case 423:
      return ApiErrorCodes.SERVICE_SUSPENDED
    case 503:
      return ApiErrorCodes.DATABASE_UNAVAILABLE
    default:
      return undefined
  }
}

export function isAuthenticatedApi(path: string) {
  return path.startsWith('/api/guest/') || path.startsWith('/api/venue/') || path.startsWith('/api/platform/')
}
