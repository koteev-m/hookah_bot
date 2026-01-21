import { clearSession, getAccessToken } from './api/auth'
import { ApiErrorCodes, type ApiErrorInfo } from './api/types'
import { getTelegramContext } from './telegram'
import { append, el } from './ui/dom'

export type AuthGateOptions = {
  root: HTMLDivElement | null
  onReady: () => void
}

type AuthGateRefs = {
  title: HTMLHeadingElement
  message: HTMLParagraphElement
  actions: HTMLDivElement
  debug: HTMLParagraphElement
}

function buildAuthGateDom(root: HTMLDivElement): AuthGateRefs {
  const main = el('main', { className: 'container' })
  const card = el('section', { className: 'card' })
  const title = el('h1', { text: 'Hookah Mini App' })
  const message = el('p', { text: 'Подготовка...' })
  const actions = el('div', { className: 'error-actions' })
  const debug = el('p', { className: 'auth-debug' })
  append(card, title, message, actions, debug)
  append(main, card)
  root.replaceChildren(main)
  return { title, message, actions, debug }
}

function renderActions(container: HTMLElement, actions: Array<{ label: string; onClick: () => void }>) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function resolveAuthMessage(error: ApiErrorInfo): string {
  if (error.code === ApiErrorCodes.INITDATA_INVALID) {
    return 'Сессия устарела. Закройте и откройте Mini App заново в Telegram.'
  }
  if (error.code === ApiErrorCodes.NETWORK_ERROR || error.status >= 500 || error.status === 0) {
    return 'Сервис недоступен, попробуйте позже'
  }
  return 'Не удалось выполнить авторизацию. Попробуйте ещё раз.'
}

export function mountAuthGate(options: AuthGateOptions) {
  const { root, onReady } = options
  if (!root) return () => undefined
  const refs = buildAuthGateDom(root)
  const isDebug = Boolean(import.meta.env.DEV)
  let disposed = false

  const renderDebug = (error: ApiErrorInfo) => {
    if (!isDebug) {
      refs.debug.textContent = ''
      return
    }
    const parts: string[] = []
    if (error.code) parts.push(`code: ${error.code}`)
    if (error.status) parts.push(`status: ${error.status}`)
    if (error.requestId) parts.push(`requestId: ${error.requestId}`)
    refs.debug.textContent = parts.join(' · ')
  }

  const renderError = (error: ApiErrorInfo) => {
    refs.message.textContent = resolveAuthMessage(error)
    renderDebug(error)
    renderActions(refs.actions, [
      {
        label: 'Повторить',
        onClick: () => void runAuth()
      },
      {
        label: 'Перезапустить',
        onClick: () => window.location.reload()
      }
    ])
  }

  const renderLoading = () => {
    refs.message.textContent = 'Авторизация...'
    refs.actions.replaceChildren()
    refs.debug.textContent = ''
  }

  const renderTelegramRequired = () => {
    refs.message.textContent = 'Откройте Mini App внутри Telegram'
    refs.actions.replaceChildren()
    refs.debug.textContent = ''
  }

  const runAuth = async () => {
    const ctx = getTelegramContext()
    if (!ctx.initData) {
      renderTelegramRequired()
      return
    }
    renderLoading()
    const result = await getAccessToken()
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.UNAUTHORIZED) {
        clearSession()
      }
      renderError(result.error)
      return
    }
    onReady()
  }

  void runAuth()

  return () => {
    disposed = true
  }
}
