import { getTelegramContext } from './telegram'

type TelegramRouter = {
  getRouteName: () => string
  back: () => void
  navigate: (hash: string) => void
  canGoBack?: () => boolean
  subscribe?: (handler: () => void) => () => void
}

export function bindTelegramBackButton(router: TelegramRouter) {
  const ctx = getTelegramContext()
  const backButton = ctx.webApp?.BackButton
  if (!backButton) {
    return () => undefined
  }

  const handleClick = () => {
    try {
      if (router.canGoBack?.()) {
        router.back()
        return
      }
      router.navigate('#/catalog')
    } catch {
      // ignore navigation errors
    }
  }

  const updateVisibility = () => {
    const routeName = router.getRouteName()
    if (routeName === 'catalog') {
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

  try {
    backButton.onClick?.(handleClick)
  } catch {
    // ignore
  }

  updateVisibility()

  const unsubscribe = router.subscribe?.(() => {
    updateVisibility()
  })

  return () => {
    if (unsubscribe) {
      unsubscribe()
    }
    try {
      backButton.offClick?.(handleClick)
    } catch {
      // ignore
    }
    try {
      backButton.hide?.()
    } catch {
      // ignore
    }
  }
}
