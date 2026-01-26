import { normalizeErrorCode } from '../api/errorMapping'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'

export type ApiErrorAction = {
  label: string
  kind?: 'primary' | 'secondary'
  onClick: () => void
}

export type ApiErrorPresentation = {
  title: string
  message: string
  severity: 'info' | 'warn' | 'error'
  actions: ApiErrorAction[]
  debugLine?: string
}

const noop = () => undefined

function buildDebugLine(error: ApiErrorInfo, isDebug: boolean): string | undefined {
  if (!isDebug) {
    return undefined
  }
  const parts: string[] = []
  const code = error.code ?? normalizeErrorCode(error)
  if (code) {
    parts.push(`code: ${code}`)
  }
  if (Number.isFinite(error.status)) {
    parts.push(`status: ${error.status}`)
  }
  if (error.requestId) {
    parts.push(`requestId: ${error.requestId}`)
  }
  return parts.length ? parts.join(' · ') : undefined
}

export function presentApiError(
  error: ApiErrorInfo,
  opts: { isDebug: boolean }
): ApiErrorPresentation {
  const code = normalizeErrorCode(error) ?? error.code
  const actions: ApiErrorAction[] = []
  let title = 'Ошибка'
  let message = 'Попробуйте ещё раз.'
  let severity: ApiErrorPresentation['severity'] = 'error'

  switch (code) {
    case ApiErrorCodes.INITDATA_INVALID:
    case ApiErrorCodes.UNAUTHORIZED:
      title = 'Сессия истекла'
      message = 'Закройте и откройте Mini App заново в Telegram.'
      actions.push({ label: 'Перезапустить', kind: 'primary', onClick: () => window.location.reload() })
      break
    case ApiErrorCodes.NETWORK_ERROR:
      title = 'Нет соединения'
      message = 'Проверьте интернет и повторите.'
      severity = 'warn'
      actions.push({ label: 'Повторить', kind: 'primary', onClick: noop })
      break
    case ApiErrorCodes.DATABASE_UNAVAILABLE:
      title = 'Сервис временно недоступен'
      message = 'Попробуйте чуть позже.'
      severity = 'warn'
      actions.push({ label: 'Повторить', kind: 'primary', onClick: noop })
      break
    case ApiErrorCodes.SERVICE_SUSPENDED:
      title = 'Заведение временно недоступно'
      message = 'Попробуйте позже.'
      severity = 'warn'
      break
    case ApiErrorCodes.SUBSCRIPTION_BLOCKED:
      title = 'Заказы временно недоступны'
      message = 'Попробуйте позже.'
      severity = 'warn'
      break
    case ApiErrorCodes.NOT_FOUND:
      title = 'Не найдено'
      message = 'Обновите QR или повторите вход.'
      severity = 'info'
      break
    case ApiErrorCodes.INVALID_INPUT:
      title = 'Проверьте ввод'
      message = error.message?.trim() ? error.message : 'Некорректные данные.'
      severity = 'warn'
      break
    default:
      title = 'Ошибка'
      message = 'Попробуйте ещё раз.'
      severity = 'error'
      break
  }

  return {
    title,
    message,
    severity,
    actions,
    debugLine: buildDebugLine(error, opts.isDebug)
  }
}
