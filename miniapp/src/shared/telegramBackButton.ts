import { getTelegramContext } from './telegram'

const E2E_BACK_EVENT = 'hookah:e2e-telegram-back'

type TelegramRouter = {
  getRouteName: () => string
  back: () => void
  navigate: (hash: string) => void
  canGoBack?: () => boolean
  subscribe?: (handler: () => void) => () => void
}

export function bindTelegramBackButton(router: TelegramRouter) {
  let backButton = getTelegramContext().webApp?.BackButton ?? null
  let isBound = false
  let retryTimer: number | null = null
  const hasDevBackEvent = import.meta.env.DEV && typeof window !== 'undefined'

  const handleClick = () => {
    try {
      if (router.canGoBack?.()) {
        router.back()
        return
      }
      const webApp = getTelegramContext().webApp
      if (webApp?.close) {
        webApp.close()
        return
      }
      router.navigate('#/catalog')
    } catch {
      // ignore navigation errors
    }
  }

  const updateVisibility = () => {
    if (!backButton) {
      return
    }
    if (!router.canGoBack?.()) {
      try {
        backButton.hide?.()
      } catch {
        // ignore
      }
      return
    }
    try {
      backButton.show?.()
    } catch {
      // ignore
    }
  }

  const tryBind = () => {
    if (isBound) {
      return
    }
    backButton = getTelegramContext().webApp?.BackButton ?? null
    if (!backButton) {
      return
    }
    try {
      backButton.onClick?.(handleClick)
      isBound = true
    } catch {
      return
    }
    updateVisibility()
  }

  tryBind()
  if (!isBound && typeof window !== 'undefined') {
    retryTimer = window.setTimeout(() => {
      retryTimer = null
      tryBind()
      updateVisibility()
    }, 0)
  }

  const unsubscribe = router.subscribe?.(() => {
    tryBind()
    updateVisibility()
  })
  if (hasDevBackEvent) {
    window.addEventListener(E2E_BACK_EVENT, handleClick)
  }

  return () => {
    if (retryTimer !== null) {
      window.clearTimeout(retryTimer)
    }
    if (unsubscribe) {
      unsubscribe()
    }
    if (hasDevBackEvent) {
      window.removeEventListener(E2E_BACK_EVENT, handleClick)
    }
    try {
      if (isBound) {
        backButton?.offClick?.(handleClick)
      }
    } catch {
      // ignore
    }
    try {
      backButton?.hide?.()
    } catch {
      // ignore
    }
  }
}
