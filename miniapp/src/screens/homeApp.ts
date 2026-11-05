import { getExplicitVenueBrowsePayload, clearVenueBrowse } from '../shared/state/venueBrowseContext'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { guestRestoreTable } from '../shared/api/guestApi'
import { platformGetMe } from '../shared/api/platformApi'
import { venueGetMe } from '../shared/api/venueApi'
import { isDebugEnabled } from '../shared/debug'
import { tableTokenQueryParamKeys } from '../shared/validation/tableToken'
import { getTelegramContext } from '../shared/telegram'
import { getTableContext, subscribe } from '../shared/state/tableContext'
import { append, el } from '../shared/ui/dom'
import { bindGuestTheme } from '../shared/ui/guestTheme'
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
  const disposeTheme = bindGuestTheme(frame)

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
      guestRestoreTable(backendUrl, deps, controller.signal)
    ])
    if (disposed) return
    if (!visit.ok || (!venue.ok && venue.error.status !== 403) || (!platform.ok && platform.error.status !== 403)) {
      fail('Не удалось проверить доступ или посещение. Повторите попытку; если сессия истекла, откройте приложение из Telegram заново.')
      return
    }
    const userId = getTelegramContext().telegramUserId
    const venueAccessValid = !venue.ok || (venue.data?.userId === userId && Array.isArray(venue.data.venues) &&
      venue.data.venues.every(item => item != null && Number.isSafeInteger(item.venueId) && item.venueId > 0 &&
        ['OWNER', 'MANAGER', 'STAFF'].includes(item.role) && Array.isArray(item.permissions) &&
        item.permissions.every(permission => typeof permission === 'string')))
    const platformAccessValid = !platform.ok || (platform.data?.ok === true && platform.data.ownerUserId === userId)
    if (!venueAccessValid || !platformAccessValid) {
      fail('Не удалось подтвердить доступные роли. Откройте приложение из Telegram заново.')
      return
    }
    const allowed: Mode[] = ['guest']
    if (venue.ok && venue.data.venues.length) allowed.push('venue')
    if (platform.ok && platform.data.ok) allowed.push('platform')
    const explicitTable = getTelegramContext().tableTokenStatus !== 'missing'
    const activeVisit = visit.data.context?.tableSessionActive === true
    const requested = new URLSearchParams(window.location.search).get('entry')
    const workModes = allowed.filter(mode => mode !== 'guest')
    const selected: Mode | null = explicitTable || activeVisit || getExplicitVenueBrowsePayload() !== null ? 'guest'
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
        const currentVisit = await guestRestoreTable(backendUrl, deps, controller.signal)
        if (disposed) return
        if (!currentVisit.ok || (mode !== 'guest' && (currentVisit.data.context?.tableSessionActive || getTableContext().tableSessionActive))) {
          status.hidden = false
          status.textContent = currentVisit.ok
            ? 'Сначала завершите текущее посещение в гостевом режиме.'
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
        const url = new URL(window.location.href)
        url.searchParams.set('mode', 'home')
        url.searchParams.set('entry', mode)
        clearVenueBrowse()
        url.searchParams.set('venue_browse', 'off')
        url.searchParams.delete('venue_entry')
        url.searchParams.delete('venueId')
        url.searchParams.delete('screen')
        for (const key of [...tableTokenQueryParamKeys, 'tableSessionId', 'table_session_id']) url.searchParams.delete(key)
        url.hash = ''
        // A fresh bootstrap re-reads roles; no cached/client role grants access.
        window.location.assign(url.href)
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
          status.textContent = 'Идёт посещение. Для перехода в рабочий режим сначала завершите визит.'
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
    disposeTheme()
  }
}
