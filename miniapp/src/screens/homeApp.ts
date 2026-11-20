import { openHomeMode, returnToGuestApplication } from '../shared/homeMode'
import { getExplicitVenueBrowsePayload } from '../shared/state/venueBrowseContext'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { platformGetMe } from '../shared/api/platformApi'
import { isVenueAccessForUser, venueGetMe } from '../shared/api/venueApi'
import { isDebugEnabled } from '../shared/debug'
import { getTelegramContext } from '../shared/telegram'
import { botVisitExitHint, checkGuestVisit, subscribe } from '../shared/state/tableContext'
import { append, el } from '../shared/ui/dom'
import { mountGuestApp } from './guestApp'
import { mountPlatformApp } from './platformApp'
import { mountVenueApp } from './venueApp'

type Mode = 'guest' | 'venue' | 'platform'
const labels: Record<Mode, string> = { guest: 'Гость', venue: 'Заведение', platform: 'Платформа' }

// This selector only chooses a surface. Each surface retains its own server access checks.
export function mountHomeApp({ root, backendUrl }: { root: HTMLDivElement | null; backendUrl: string }) {
  if (!root) return () => undefined
  const controller = new AbortController()
  const deps = { isDebug: isDebugEnabled(), getAccessToken, clearSession }
  let disposeApp: (() => void) | undefined
  let unsubscribe: (() => void) | undefined
  let disposed = false
  let canLeaveMode = () => true
  const status = el('p', { text: 'Проверяем доступ и текущее посещение…' })
  const actions = el('nav', { className: 'home-modes' })
  actions.setAttribute('aria-label', 'Режим приложения')
  const content = el('div')
  const frame = el('div', { className: 'home-entry' })
  append(frame, status, actions, content)
  root.replaceChildren(frame)

  const fail = (message: string) => {
    status.textContent = message
    const retry = el('button', { text: 'Повторить' })
    retry.addEventListener('click', () => window.location.reload())
    actions.replaceChildren(retry)
  }
  const start = async () => {
    const [venue, platform, visit] = await Promise.all([
      venueGetMe(backendUrl, deps, controller.signal),
      platformGetMe(backendUrl, deps, controller.signal),
      checkGuestVisit(backendUrl, controller.signal)
    ])
    if (disposed) return
    const launch = getTelegramContext()
    const explicitQr = launch.tableTokenStatus === 'valid' && launch.tableTokenAutoResolve
    if ((visit === 'unknown' && !explicitQr && window.location.hash !== '#/account/venue-application' &&
        !window.history.state?.hookahGuestNavigation) || (!venue.ok && venue.error.status !== 403) || (!platform.ok && platform.error.status !== 403)) {
      fail('Не удалось проверить доступ или посещение. Повторите попытку; если сессия истекла, откройте приложение из Telegram заново.')
      return
    }
    const userId = getTelegramContext().telegramUserId
    const venueAccessValid = !venue.ok || isVenueAccessForUser(venue.data, userId)
    const platformAccessValid = !platform.ok || (platform.data?.ok === true && platform.data.ownerUserId === userId)
    if (!venueAccessValid || !platformAccessValid) {
      fail('Не удалось подтвердить доступные роли. Откройте приложение из Telegram заново.')
      return
    }
    const allowed: Mode[] = ['guest']
    if (venue.ok && venue.data.venues.length) allowed.push('venue')
    if (platform.ok && platform.data.ok) allowed.push('platform')
    const explicitTable = getTelegramContext().tableTokenStatus !== 'missing'
    const activeVisit = visit === 'active'
    const params = new URLSearchParams(window.location.search)
    const requested = params.get('entry')
    if (requested === 'venue' && params.get('from') === 'venue-application' &&
        (!venue.ok || !venue.data.venues.some(item => item.role === 'OWNER' && String(item.venueId) === params.get('venueId')))) {
      returnToGuestApplication()
      return
    }
    const workModes = allowed.filter(mode => mode !== 'guest')
    const selected: Mode | null = visit === 'unknown' || explicitTable || activeVisit || getExplicitVenueBrowsePayload() !== null ||
      window.location.hash === '#/account/venue-application' ? 'guest'
      : requested && allowed.includes(requested as Mode) ? requested as Mode
      : workModes.length > 1 ? null : workModes[0] ?? 'guest'
    status.textContent = ''
    status.hidden = true
    let switching = false
    const buttons: HTMLButtonElement[] = []
    for (const mode of allowed) {
      const button = el('button', { text: labels[mode] })
      button.setAttribute('aria-pressed', String(mode === selected))
      button.disabled = mode === selected
      button.addEventListener('click', async () => {
        if (switching) return
        switching = true
        buttons.forEach(item => { item.disabled = true })
        // Recheck server-owned visit state; an active visit permits Guest but blocks work modes.
        const currentVisit = await checkGuestVisit(backendUrl, controller.signal)
        if (disposed) return
        if (currentVisit === 'unknown' || (mode !== 'guest' && currentVisit === 'active')) {
          status.hidden = false
          status.textContent = currentVisit === 'active'
            ? 'Сначала завершите текущее посещение в гостевом режиме. ' + botVisitExitHint
            : 'Не удалось проверить посещение. Повторите попытку.'
          switching = false
          buttons.forEach((item, index) => { item.disabled = allowed[index] === selected })
          return
        }

        if (selected !== null && (!window.confirm('Перейти в другой режим? Несохранённые изменения на текущем экране будут потеряны.') || !canLeaveMode())) {
          status.hidden = false
          status.textContent = 'Переход отменён. Завершите текущую операцию или сохраните изменения перед сменой режима.'
          switching = false
          buttons.forEach((item, index) => { item.disabled = allowed[index] === selected })
          return
        }
        openHomeMode(mode)
      })
      buttons.push(button)
      append(actions, button)
    }
    if (allowed.length === 1) actions.hidden = true
    if (requested && !allowed.includes(requested as Mode) && !activeVisit && !explicitTable) {
      status.hidden = false
      status.textContent = 'Выбранный режим больше недоступен. Доступные режимы проверены заново.'
    }
    if (selected === null) {
      frame.classList.add('home-mode-picker')
      const choice = el('section', { className: 'home-mode-choice' })
      append(choice, el('h1', { text: 'Как открыть приложение?' }),
        el('p', { text: 'У вас несколько рабочих ролей. Выберите заведение или управление платформой. В гостевом режиме можно открыть меню и свой визит.' }))
      content.replaceChildren(choice)
      choice.appendChild(actions)
    } else if (selected === 'guest') {
      disposeApp = mountGuestApp({ root: content })
      unsubscribe = subscribe(snapshot => {
        if (snapshot.tableSessionActive && allowed.length > 1) {
          status.hidden = false
          status.textContent = 'Идёт посещение. Для перехода в рабочий режим сначала завершите визит. ' + botVisitExitHint
        } else if (snapshot.status !== 'resolving' && snapshot.status !== 'error') {
          status.hidden = !(requested && !allowed.includes(requested as Mode))
          if (!status.hidden) status.textContent = 'Выбранный режим больше недоступен. Доступные режимы проверены заново.'
        }
      })
    } else if (selected === 'venue') {
      disposeApp = mountVenueApp({ root: content, backendUrl, registerModeExitGuard: guard => { canLeaveMode = guard } })
    } else {
      disposeApp = mountPlatformApp({ root: content, backendUrl })
    }
  }
  void start()
  return () => {
    disposed = true
    controller.abort()
    unsubscribe?.()
    disposeApp?.()
  }
}
