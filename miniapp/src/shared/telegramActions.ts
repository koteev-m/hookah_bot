import type { TelegramContext } from './telegram'

type TelegramActionResult = { ok: true } | { ok: false; reason: string }

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

export function openBotChat(ctx: TelegramContext): TelegramActionResult {
  if (!ctx.botUsername) {
    return { ok: false, reason: 'bot_username_missing' }
  }
  const url = `https://t.me/${ctx.botUsername}`
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
