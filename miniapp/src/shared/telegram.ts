import { validateTableToken } from './validation/tableToken'

const tableTokenSessionStorageKey = 'hookah_guest_table_token'
const tableTokenLocalStorageKey = 'hookah_guest_table_token'

type TelegramInitDataUnsafe = {
  start_param?: string
  startParam?: string
  user?: {
    id?: number
  }
}

type TelegramScanQrPopupParams = {
  text?: string
}

type TelegramScanQrPopupCallback = (scannedText: string) => boolean | void
type TelegramScanQrPopup = (
  params: TelegramScanQrPopupParams,
  callback?: TelegramScanQrPopupCallback
) => void

export type TelegramWebAppLike = {
  initData?: string
  initDataUnsafe?: TelegramInitDataUnsafe
  ready?: () => void
  expand?: () => void
  showScanQrPopup?: TelegramScanQrPopup
  closeScanQrPopup?: () => void
  sendData?: (data: string) => void
  openTelegramLink?: (url: string) => void
  showAlert?: (message: string) => void
  BackButton?: {
    isVisible?: boolean
    show?: () => void
    hide?: () => void
    onClick?: (cb: () => void) => void
    offClick?: (cb: () => void) => void
  }
}

export type TelegramContext = {
  isTelegram: boolean
  initData: string | null
  tableToken: string | null
  tableSessionId: number | null
  tableTokenStatus: 'missing' | 'invalid' | 'valid'
  tableTokenAutoResolve: boolean
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

export function getTelegramQrScanner(webApp: TelegramWebAppLike | null): TelegramScanQrPopup | null {
  const scanner = webApp?.showScanQrPopup
  if (!webApp || typeof scanner !== 'function') {
    return null
  }
  return (params, callback) => {
    scanner.call(webApp, params, callback)
  }
}

function getSearchParams(): URLSearchParams | null {
  if (typeof window === 'undefined') {
    return null
  }
  return new URLSearchParams(window.location.search)
}

function setSessionStorageToken(token: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.sessionStorage.setItem(tableTokenSessionStorageKey, token)
  } catch {
    // ignore session storage errors
  }
}

function setLocalStorageToken(token: string): void {
  if (typeof window === 'undefined') {
    return
  }
  try {
    window.localStorage.setItem(tableTokenLocalStorageKey, token)
  } catch {
    // ignore local storage errors
  }
}

export function rememberTableToken(token: string): void {
  const validation = validateTableToken(token)
  if (!validation.ok) {
    return
  }
  setSessionStorageToken(validation.value)
  setLocalStorageToken(validation.value)
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

function parsePositiveNumber(value: string | null | undefined): number | null {
  const normalized = normalizeNonEmpty(value)
  if (!normalized || !/^\d+$/.test(normalized)) {
    return null
  }
  const parsed = Number(normalized)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

export function getTelegramContext(): TelegramContext {
  const webApp = getWebApp()
  const params = getSearchParams()
  const initDataFromWebApp = webApp?.initData ? webApp.initData : null
  const initData =
    initDataFromWebApp ??
    (() => {
      if (typeof window === 'undefined') {
        return null
      }
      const rawHash = window.location.hash ?? ''
      const hash = rawHash.startsWith('#') ? rawHash.slice(1) : rawHash
      if (!hash) {
        return null
      }
      const tgWebAppData = new URLSearchParams(hash).get('tgWebAppData')
      if (!tgWebAppData) {
        return null
      }
      try {
        return decodeURIComponent(tgWebAppData)
      } catch {
        return tgWebAppData
      }
    })()
  const startParamCandidates: Array<{ value: string | null | undefined; source: string }> = [
    { value: getQueryParam(params, 'tableToken'), source: 'query_tableToken' },
    { value: getQueryParam(params, 'table_token'), source: 'query_table_token' },
    { value: getQueryParam(params, 'tgWebAppStartParam'), source: 'query_tgWebAppStartParam' },
    { value: getQueryParam(params, 'startapp'), source: 'query_startapp' },
    { value: getQueryParam(params, 'start_param'), source: 'query_start_param' },
    { value: webApp?.initDataUnsafe?.start_param, source: 'initDataUnsafe_start_param' },
    { value: webApp?.initDataUnsafe?.startParam, source: 'initDataUnsafe_startParam' }
  ]
  const selectedStartParam = startParamCandidates.find((candidate) => normalizeNonEmpty(candidate.value))
  const startParamSource = selectedStartParam?.source ?? null
  const startParam = normalizeNonEmpty(selectedStartParam?.value)
  const screen = normalizeNonEmpty(getQueryParam(params, 'screen'))
  const hasExplicitStartSignal =
    startParamSource !== null &&
    startParamSource !== 'query_tableToken' &&
    startParamSource !== 'query_table_token'
  const tableTokenAutoResolve = Boolean(startParam) && (screen === 'menu' || hasExplicitStartSignal)
  let tableToken: string | null = null
  let tableTokenStatus: TelegramContext['tableTokenStatus'] = 'missing'
  if (!startParam) {
    tableTokenStatus = 'missing'
  } else {
    const validation = validateTableToken(startParam)
    if (!validation.ok) {
      tableTokenStatus = 'invalid'
    } else {
      tableTokenStatus = 'valid'
      tableToken = validation.value
    }
  }
  const tableSessionId =
    parsePositiveNumber(getQueryParam(params, 'tableSessionId')) ??
    parsePositiveNumber(getQueryParam(params, 'table_session_id'))
  const botUsername = normalizeNonEmpty(getQueryParam(params, 'tgWebAppBotUsername'))
  const rawUserId = webApp?.initDataUnsafe?.user?.id
  const telegramUserId = typeof rawUserId === 'number' && rawUserId > 0 ? rawUserId : null
  return {
    isTelegram: Boolean(webApp),
    initData,
    tableToken,
    tableSessionId,
    tableTokenStatus,
    tableTokenAutoResolve,
    startParam,
    botUsername,
    telegramUserId,
    webApp
  }
}

export function getTelegramInitData(): string | null {
  return getTelegramContext().initData
}
