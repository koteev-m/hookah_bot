type TelegramInitDataUnsafe = {
  start_param?: string
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
  const startParam =
    getQueryParam(params, 'tableToken') ??
    getQueryParam(params, 'tgWebAppStartParam') ??
    (webApp?.initDataUnsafe?.start_param ?? null)
  const botUsername = getQueryParam(params, 'tgWebAppBotUsername')
  return {
    isTelegram: Boolean(webApp),
    initData,
    startParam,
    botUsername,
    webApp
  }
}

export function getTelegramInitData(): string | null {
  return getTelegramContext().initData
}
