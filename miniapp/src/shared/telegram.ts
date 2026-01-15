export type TelegramContext = {
  initDataLength: number
  startParam: string
  userId?: number
}

export function getTelegramContext(): TelegramContext {
  const tg = (window as any).Telegram?.WebApp
  const initData = tg?.initData || ''
  const startParam = tg?.initDataUnsafe?.start_param || tg?.initDataUnsafe?.startParam || ''
  const userId = tg?.initDataUnsafe?.user?.id
  return { initDataLength: initData.length, startParam, userId }
}

export function getTelegramInitData(): string {
  const tg = (window as any).Telegram?.WebApp
  return tg?.initData || ''
}
