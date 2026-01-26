import { clearSession, getAccessToken } from './api/auth'
import { ApiErrorCodes, type ApiErrorInfo } from './api/types'
import { isDebugEnabled } from './debug'
import { getTelegramContext } from './telegram'
import { append, el } from './ui/dom'
import { presentApiError } from './ui/apiErrorPresenter'

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

export function mountAuthGate(options: AuthGateOptions) {
  const { root, onReady } = options
  if (!root) return () => undefined
  const refs = buildAuthGateDom(root)
  const isDebug = isDebugEnabled()
  let disposed = false

  const renderError = (error: ApiErrorInfo) => {
    const presentation = presentApiError(error, { isDebug })
    refs.title.textContent = presentation.title
    refs.message.textContent = presentation.message
    refs.debug.textContent = presentation.debugLine ?? ''
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void runAuth() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void runAuth() })
    }
    renderActions(refs.actions, actions)
  }

  const renderLoading = () => {
    refs.title.textContent = 'Hookah Mini App'
    refs.message.textContent = 'Авторизация...'
    refs.actions.replaceChildren()
    refs.debug.textContent = ''
  }

  const renderTelegramRequired = () => {
    refs.title.textContent = 'Hookah Mini App'
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
