import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { platformGetMe } from '../shared/api/platformApi'
import type { PlatformMeResponse } from '../shared/api/platformDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { isDebugEnabled } from '../shared/debug'
import { parsePositiveInt } from '../shared/parse'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { append, el } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { renderPlatformCreateVenueScreen } from './platformCreateVenue'
import { renderPlatformVenueDetailScreen } from './platformVenueDetail'
import { renderPlatformVenuesListScreen } from './platformVenuesList'

export type PlatformAppOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type RouteName = 'venues' | 'venue' | 'create'

type Route = {
  name: RouteName
  venueId: number | null
}

type PlatformShellRefs = {
  app: HTMLDivElement
  accessState: HTMLParagraphElement
  content: HTMLDivElement
  errorCard: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function ensureDefaultHash() {
  const hash = window.location.hash
  if (!hash || hash === '#' || hash === '#/') {
    window.location.hash = '#/venues'
  }
}

function resolveRoute(): Route {
  const rawHash = window.location.hash.replace(/^#/, '')
  const cleaned = rawHash.startsWith('/') ? rawHash.slice(1) : rawHash
  if (!cleaned) {
    return { name: 'venues', venueId: null }
  }
  const [pathPart] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (!route || !['venues', 'venue', 'create'].includes(route)) {
    return { name: 'venues', venueId: null }
  }
  if (route === 'venue') {
    const venueId = parsePositiveInt(segments[1])
    return { name: 'venue', venueId: venueId ?? null }
  }
  return { name: route, venueId: null }
}

function renderErrorActions(container: HTMLElement, actions: ApiErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    if (action.kind === 'secondary') {
      button.classList.add('button-secondary')
    }
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildPlatformShell(root: HTMLDivElement): PlatformShellRefs {
  const app = el('div', { className: 'venue-shell' })
  const header = el('header', { className: 'venue-header' })
  const brand = el('div', { className: 'app-brand' })
  const title = el('h1', { text: 'Hookah Mini App' })
  const subtitle = el('p', { className: 'app-subtitle', text: 'Режим платформы' })
  append(brand, title, subtitle)

  const controls = el('div', { className: 'venue-controls' })
  const accessState = el('p', { className: 'venue-access-state', text: 'Проверяем доступ...' })
  append(controls, accessState)

  const errorCard = el('div', { className: 'error-card venue-error' }) as HTMLDivElement
  errorCard.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(errorCard, errorTitle, errorMessage, errorActions, errorDetails)

  const content = el('div', { className: 'container venue-content' })
  append(header, brand, controls)
  append(app, header, errorCard, content)
  root.replaceChildren(app)

  return {
    app,
    accessState,
    content,
    errorCard,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

export function mountPlatformApp(options: PlatformAppOptions) {
  const { root, backendUrl } = options
  if (!root) return () => undefined
  ensureDefaultHash()
  const isDebug = isDebugEnabled()
  const deps = buildApiDeps(isDebug)
  const refs = buildPlatformShell(root)

  let disposed = false
  let accessAbort: AbortController | null = null
  let access: PlatformMeResponse | null = null
  let hasAccess = false

  const setAccessState = (text: string) => {
    refs.accessState.textContent = text
  }

  const showError = (error: ApiErrorInfo, retry: () => void) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.errorCard.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) => (action.label === 'Повторить' ? { ...action, onClick: retry } : action))
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.errorCard.hidden = false
  }

  const hideError = () => {
    refs.errorCard.hidden = true
  }

  const renderNoAccess = () => {
    refs.content.replaceChildren(el('div', { className: 'card', text: 'Нет доступа.' }))
  }

  const loadAccess = async () => {
    accessAbort?.abort()
    accessAbort = new AbortController()
    hideError()
    setAccessState('Проверяем доступ...')
    const result = await platformGetMe(backendUrl, deps, accessAbort.signal)
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      const normalized = normalizeErrorCode(result.error)
      if (normalized === ApiErrorCodes.FORBIDDEN) {
        hasAccess = false
        setAccessState('Нет доступа')
        renderNoAccess()
        return
      }
      setAccessState('Ошибка доступа')
      showError(result.error, loadAccess)
      return
    }
    access = result.data
    hasAccess = true
    setAccessState(`Platform owner #${access.ownerUserId}`)
  }

  const navigate = (hash: string) => {
    if (hash === '#/catalog') {
      window.location.hash = '#/venues'
      return
    }
    window.location.hash = hash
  }

  let currentDispose: (() => void) | null = null
  let currentRoute = resolveRoute()

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const router = {
    getRouteName: () => (currentRoute.name === 'venues' ? 'catalog' : currentRoute.name),
    navigate,
    back: () => navigate('#/venues'),
    canGoBack: () => currentRoute.name !== 'venues',
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const renderRouteContent = (route: Route) => {
    if (!hasAccess) {
      renderNoAccess()
      return () => undefined
    }
    switch (route.name) {
      case 'venues':
        return renderPlatformVenuesListScreen({
          root: refs.content,
          backendUrl,
          isDebug,
          onNavigate: navigate
        })
      case 'create':
        return renderPlatformCreateVenueScreen({
          root: refs.content,
          backendUrl,
          isDebug,
          onNavigate: navigate
        })
      case 'venue':
        if (!route.venueId) {
          navigate('#/venues')
          return () => undefined
        }
        return renderPlatformVenueDetailScreen({
          root: refs.content,
          backendUrl,
          isDebug,
          venueId: route.venueId
        })
    }
  }

  const render = () => {
    const route = resolveRoute()
    currentRoute = route
    currentDispose?.()
    currentDispose = renderRouteContent(route)
  }

  const onHashChange = () => {
    render()
    notifyRouteChange()
  }

  window.addEventListener('hashchange', onHashChange)
  void loadAccess().then(() => {
    if (!disposed) {
      render()
      notifyRouteChange()
    }
  })

  return () => {
    disposed = true
    accessAbort?.abort()
    accessAbort = null
    currentDispose?.()
    unbindBackButton()
    window.removeEventListener('hashchange', onHashChange)
  }
}
