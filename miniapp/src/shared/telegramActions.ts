import type { TelegramContext } from './telegram'

type TelegramActionResult = { ok: true } | { ok: false; reason: string }
type OpenBotChatOptions = {
  tableToken?: string | null
  tableSessionId?: number | null
}

export function canSendData(ctx: TelegramContext): boolean {
  return Boolean(ctx.webApp?.sendData)
}

export function sendChatOrder(ctx: TelegramContext, payload: unknown): TelegramActionResult {
  if (!ctx.webApp?.sendData) {
    return { ok: false, reason: 'sendData_unavailable' }
  }
  try {
    const data = JSON.stringify(payload)
    ctx.webApp.sendData(data)
    return { ok: true }
  } catch {
    return { ok: false, reason: 'sendData_failed' }
  }
}

function buildBotChatUrl(ctx: TelegramContext, options?: OpenBotChatOptions): string | null {
  if (!ctx.botUsername) {
    return null
  }
  const params = new URLSearchParams()
  const tableToken = options?.tableToken?.trim()
  if (tableToken) {
    params.set('start', tableToken)
  }
  if (typeof options?.tableSessionId === 'number' && Number.isFinite(options.tableSessionId)) {
    params.set('table_session_id', String(options.tableSessionId))
  }
  const query = params.toString()
  return query ? `https://t.me/${ctx.botUsername}?${query}` : `https://t.me/${ctx.botUsername}`
}

export function openBotChat(ctx: TelegramContext, options?: OpenBotChatOptions): TelegramActionResult {
  const url = buildBotChatUrl(ctx, options)
  if (!url) {
    return { ok: false, reason: 'bot_username_missing' }
  }
  if (ctx.webApp?.openTelegramLink) {
    try {
      ctx.webApp.openTelegramLink(url)
      return { ok: true }
    } catch {
      return { ok: false, reason: 'openTelegramLink_failed' }
    }
  }
  try {
    if (typeof window !== 'undefined') {
      window.location.href = url
      return { ok: true }
    }
    return { ok: false, reason: 'window_unavailable' }
  } catch {
    return { ok: false, reason: 'open_link_failed' }
  }
}
