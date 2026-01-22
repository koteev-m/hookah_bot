import { normalizeTableToken } from './tableToken'

type TelegramInitDataUnsafe = {
  start_param?: string
  startParam?: string
  user?: {
    id?: number
  }
}

export type TelegramWebAppLike = {
  initData?: string
  initDataUnsafe?: TelegramInitDataUnsafe
  ready?: () => void
  expand?: () => void
}

export type TelegramContext = {
  isTelegram: boolean
  initData: string | null
  startParam: string | null
  botUsername: string | null
  telegramUserId: number | null
  webApp: TelegramWebAppLike | null
}

type TelegramWindow = Window & {
  Telegram?: {
    WebApp?: TelegramWebAppLike
  }
}

function getWebApp(): TelegramWebAppLike | null {
  if (typeof window === 'undefined') {
    return null
  }
  return (window as TelegramWindow).Telegram?.WebApp ?? null
}

function getSearchParams(): URLSearchParams | null {
  if (typeof window === 'undefined') {
    return null
  }
  return new URLSearchParams(window.location.search)
}

function normalizeNonEmpty(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null
  }
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function getQueryParam(params: URLSearchParams | null, key: string): string | null {
  if (!params) {
    return null
  }
  const value = params.get(key)
  return value ? value : null
}

export function getTelegramContext(): TelegramContext {
  const webApp = getWebApp()
  const params = getSearchParams()
  const initData = webApp?.initData ? webApp.initData : null
  const tableToken = normalizeTableToken(getQueryParam(params, 'tableToken'))
  const startParam = normalizeNonEmpty(
    tableToken ??
      getQueryParam(params, 'tgWebAppStartParam') ??
      getQueryParam(params, 'startapp') ??
      getQueryParam(params, 'start_param') ??
      webApp?.initDataUnsafe?.start_param ??
      webApp?.initDataUnsafe?.startParam
  )
  const botUsername = normalizeNonEmpty(getQueryParam(params, 'tgWebAppBotUsername'))
  const rawUserId = webApp?.initDataUnsafe?.user?.id
  const telegramUserId = typeof rawUserId === 'number' && rawUserId > 0 ? rawUserId : null
  return {
    isTelegram: Boolean(webApp),
    initData,
    startParam,
    botUsername,
    telegramUserId,
    webApp
  }
}

export function getTelegramInitData(): string | null {
  return getTelegramContext().initData
}
