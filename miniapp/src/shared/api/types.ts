export const ApiErrorCodes = {
  UNAUTHORIZED: 'UNAUTHORIZED',
  INVALID_INPUT: 'INVALID_INPUT',
  NOT_FOUND: 'NOT_FOUND',
  SERVICE_SUSPENDED: 'SERVICE_SUSPENDED',
  DATABASE_UNAVAILABLE: 'DATABASE_UNAVAILABLE',
  CONFIG_ERROR: 'CONFIG_ERROR',
  INTERNAL_ERROR: 'INTERNAL_ERROR',
  INITDATA_INVALID: 'INITDATA_INVALID',
  NETWORK_ERROR: 'NETWORK_ERROR'
} as const

export type ApiErrorCode = (typeof ApiErrorCodes)[keyof typeof ApiErrorCodes]

export type ApiErrorEnvelope = {
  error?: {
    code?: string
    message?: string
    details?: unknown
  }
  requestId?: string
}

export type ApiErrorInfo = {
  status: number
  code?: string
  message?: string
  requestId?: string
}

export type ApiResult<T> =
  | { ok: true; data: T }
  | { ok: false; error: ApiErrorInfo }

export const REQUEST_ID_HEADER = 'X-Request-Id'
