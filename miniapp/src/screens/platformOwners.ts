import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import {
  platformGetOperationalOwner,
  platformListOperationalOwners
} from '../shared/api/platformApi'
import type { PlatformOperationalOwnerDto } from '../shared/api/platformDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError } from '../shared/ui/apiErrorPresenter'
import { formatVenueCount } from '../shared/ui/venueCount'

type CommonOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigate: (hash: string) => void
}

export type PlatformOwnersListOptions = CommonOptions

export type PlatformOwnerDetailOptions = CommonOptions & {
  userId: number
}

function deps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function ownerName(owner: PlatformOperationalOwnerDto) {
  const fullName = [owner.firstName?.trim(), owner.lastName?.trim()].filter(Boolean).join(' ')
  if (fullName) return fullName
  if (owner.username?.trim()) return `@${owner.username.replace(/^@/, '')}`
  return `User #${owner.userId}`
}

function statusCounts(owner: PlatformOperationalOwnerDto) {
  const entries = Object.entries(owner.venueStatusCounts)
  return entries.length ? entries.map(([status, count]) => `${status}: ${count}`).join(' · ') : 'Нет активных заведений'
}

function labeledControl<T extends HTMLInputElement | HTMLSelectElement>(
  id: string,
  labelText: string,
  control: T
) {
  const field = el('div', { className: 'ownership-field' })
  const label = el('label', { className: 'field-label', text: labelText }) as HTMLLabelElement
  control.id = id
  label.htmlFor = id
  append(field, label, control)
  return field
}

function configureLiveRegions(status: HTMLParagraphElement, error: HTMLDivElement) {
  status.setAttribute('role', 'status')
  status.setAttribute('aria-live', 'polite')
  status.setAttribute('aria-atomic', 'true')
  error.setAttribute('role', 'alert')
  error.setAttribute('aria-live', 'assertive')
  error.setAttribute('aria-atomic', 'true')
  error.tabIndex = -1
}

function showError(
  container: HTMLDivElement,
  error: ApiErrorInfo,
  isDebug: boolean,
  retry?: () => void
) {
  const presentation = presentApiError(error, { isDebug, scope: 'venue' })
  const actions = el('div', { className: 'error-actions' })
  presentation.actions.forEach((action) => {
    const button = el('button', {
      className: action.kind === 'secondary' ? 'button-secondary' : undefined,
      text: action.label
    }) as HTMLButtonElement
    button.addEventListener('click', action.label === 'Повторить' && retry ? retry : action.onClick)
    actions.appendChild(button)
  })
  container.hidden = false
  container.dataset.severity = presentation.severity
  container.replaceChildren(
    el('h3', { text: presentation.title }),
    el('p', { text: presentation.message }),
    actions
  )
  container.focus({ preventScroll: true })
}

function ownerWorkspaceNav(onNavigate: (hash: string) => void) {
  const nav = el('div', { className: 'venue-inline-actions ownership-tabs' })
  const requests = el('button', { className: 'button-small button-secondary', text: 'Заявки' }) as HTMLButtonElement
  const venues = el('button', { className: 'button-small button-secondary', text: 'Кальянные' }) as HTMLButtonElement
  const owners = el('button', { className: 'button-small', text: 'Владельцы' }) as HTMLButtonElement
  requests.addEventListener('click', () => onNavigate('#/applications'))
  venues.addEventListener('click', () => onNavigate('#/venues'))
  owners.addEventListener('click', () => onNavigate('#/owners'))
  append(nav, requests, venues, owners)
  return nav
}

export function renderPlatformOwnersListScreen(options: PlatformOwnersListOptions) {
  const { root, backendUrl, isDebug, onNavigate } = options
  if (!root) return () => undefined
  const wrapper = el('div', { className: 'venue-orders ownership-workspace' })
  const header = el('section', { className: 'card' })
  const filters = el('div', { className: 'venue-form-grid' })
  const search = document.createElement('input')
  search.type = 'search'
  search.className = 'venue-input'
  search.placeholder = 'Имя, username или user id'
  const statusFilter = document.createElement('select')
  statusFilter.className = 'venue-select'
  ;['any', 'DRAFT', 'PUBLISHED', 'HIDDEN', 'PAUSED', 'SUSPENDED', 'ARCHIVED'].forEach((status) => {
    statusFilter.appendChild(new Option(status === 'any' ? 'Все статусы' : status, status))
  })
  append(
    filters,
    labeledControl('platform-owners-search', 'Поиск владельцев', search),
    labeledControl('platform-owners-status', 'Статус заведения', statusFilter)
  )
  append(
    header,
    el('h2', { text: 'Операционные владельцы' }),
    el('p', {
      text:
        'Список строится только из действующих venue_members(role=OWNER). Commercial account и quota не определяют операционное владение.'
    }),
    ownerWorkspaceNav(onNavigate),
    filters
  )
  const status = el('p', { className: 'status' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  configureLiveRegions(status, error)
  const list = el('div', { className: 'venue-orders-list' }) as HTMLDivElement
  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  const requestDeps = deps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let seq = 0
  let debounce: ReturnType<typeof setTimeout> | null = null

  const load = async () => {
    controller?.abort()
    const active = new AbortController()
    controller = active
    const current = ++seq
    status.textContent = 'Загрузка...'
    error.hidden = true
    const result = await platformListOperationalOwners(
      backendUrl,
      {
        q: search.value.trim() || null,
        status: statusFilter.value === 'any' ? null : statusFilter.value,
        limit: 100,
        offset: 0
      },
      requestDeps,
      active.signal
    )
    if (disposed || current !== seq) return
    controller = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      status.textContent = ''
      showError(error, result.error, isDebug, () => void load())
      return
    }
    status.textContent = `Найдено: ${result.data.owners.length}`
    list.replaceChildren()
    if (!result.data.owners.length) {
      list.appendChild(el('p', { className: 'venue-empty', text: 'Владельцы не найдены.' }))
      return
    }
    result.data.owners.forEach((owner) => {
      const row = el('div', { className: 'venue-order-row' })
      const meta = el('div', { className: 'venue-order-meta' })
      append(
        meta,
        el('strong', { text: ownerName(owner) }),
        el('p', { className: 'venue-order-sub', text: `User #${owner.userId} · ${formatVenueCount(owner.venueCount)}` }),
        el('p', { className: 'venue-order-sub', text: statusCounts(owner) })
      )
      const open = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      open.addEventListener('click', () => onNavigate(`#/owner/${owner.userId}`))
      append(row, meta, open)
      list.appendChild(row)
    })
  }

  const schedule = () => {
    if (debounce) clearTimeout(debounce)
    debounce = setTimeout(() => void load(), 250)
  }
  const disposables = [on(search, 'input', schedule), on(statusFilter, 'change', () => void load())]
  void load()
  return () => {
    disposed = true
    controller?.abort()
    if (debounce) clearTimeout(debounce)
    disposables.forEach((dispose) => dispose())
  }
}

export function renderPlatformOwnerDetailScreen(options: PlatformOwnerDetailOptions) {
  const { root, backendUrl, isDebug, onNavigate, userId } = options
  if (!root) return () => undefined
  const wrapper = el('div', { className: 'venue-orders ownership-workspace' })
  const header = el('section', { className: 'card' })
  const back = el('button', { className: 'button-small button-secondary', text: '← К владельцам' }) as HTMLButtonElement
  back.addEventListener('click', () => onNavigate('#/owners'))
  append(header, back, el('h2', { text: `Владелец #${userId}` }), ownerWorkspaceNav(onNavigate))
  const status = el('p', { className: 'status', text: 'Загрузка...' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  configureLiveRegions(status, error)
  const content = el('div') as HTMLDivElement
  append(wrapper, header, status, error, content)
  root.replaceChildren(wrapper)
  const requestDeps = deps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let seq = 0

  const load = async () => {
    controller?.abort()
    const active = new AbortController()
    controller = active
    const current = ++seq
    status.textContent = 'Загрузка...'
    error.hidden = true
    content.replaceChildren()
    const result = await platformGetOperationalOwner(backendUrl, userId, requestDeps, active.signal)
    if (disposed || current !== seq) return
    controller = null
    if (!result.ok) {
      status.textContent = ''
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(error, result.error, isDebug, () => void load())
      }
      return
    }
    const { owner, venues } = result.data
    status.textContent = ''
    const summary = el('section', { className: 'card' })
    append(
      summary,
      el('h3', { text: ownerName(owner) }),
      el('p', { text: `User #${owner.userId}` }),
      el('p', { text: `В управлении: ${formatVenueCount(owner.venueCount)}` }),
      el('p', { text: statusCounts(owner) })
    )
    const venueList = el('section', { className: 'card' })
    append(venueList, el('h3', { text: 'Связанные заведения' }))
    const rows = el('div', { className: 'venue-orders-list' })
    venues.forEach((venue) => {
      const row = el('div', { className: 'venue-order-row' })
      const meta = el('div', { className: 'venue-order-meta' })
      append(
        meta,
        el('strong', { text: venue.name }),
        el('p', { className: 'venue-order-sub', text: [`#${venue.id}`, venue.city, venue.status].filter(Boolean).join(' · ') })
      )
      const open = el('button', { className: 'button-small', text: 'Открыть venue' }) as HTMLButtonElement
      open.addEventListener('click', () => onNavigate(`#/venue/${venue.id}`))
      append(row, meta, open)
      rows.appendChild(row)
    })
    if (!venues.length) rows.appendChild(el('p', { className: 'venue-empty', text: 'Нет активных заведений.' }))
    venueList.appendChild(rows)
    append(content, summary, venueList)
  }

  void load()

  return () => {
    disposed = true
    controller?.abort()
  }
}
