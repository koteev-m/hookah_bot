import { renderOwnStaffPhotoScreen, type StaffPhotoNavigation } from './staffPhoto'
import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetConversationUnreadCount, venueGetMe } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { isDebugEnabled } from '../shared/debug'
import { parsePositiveInt } from '../shared/parse'
import { getTelegramContext } from '../shared/telegram'
import { bindTelegramBackButton } from '../shared/telegramBackButton'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { renderVenueChatLinkScreen } from './venueChatLink'
import { renderVenueCallsScreen } from './venueCalls'
import { renderVenueBookingsScreen } from './venueBookings'
import { renderVenueDashboardScreen } from './venueDashboard'
import { renderVenueFeedbackScreen } from './venueFeedback'
import { renderVenueGuestPreviewScreen } from './venueGuestPreview'
import { renderVenueMenuScreen } from './venueMenu'
import { renderVenueMessagesScreen } from './venueMessages'
import { renderVenueOrderDetailScreen } from './venueOrderDetail'
import { renderVenueOrdersScreen } from './venueOrders'
import { renderVenueOwnershipScreen } from './venueOwnership'
import { renderVenuePromotionsScreen } from './venuePromotions'
import { renderVenueSettingsCatalog, SETTINGS_SECTIONS, type VenueSettingsSection } from './venueSettingsCatalog'
import { renderVenueSettingsScreen, type VenueSettingsDraftState } from './venueSettings'
import { renderVenueShiftExtensionsScreen } from './venueShiftExtensions'
import { renderVenueStaffScreen } from './venueStaff'
import { renderVenueStaffScheduleScreen, type ScheduleNavigation } from './venueStaffSchedule'
import { renderVenueStatsScreen } from './venueStats'
import { renderVenueSubscriptionScreen } from './venueSubscription'
import { renderVenueTablesScreen } from './venueTables'

export type VenueAppOptions = {
  root: HTMLDivElement | null
  backendUrl: string
}

type RouteName =
  | 'dashboard'
  | 'more'
  | 'ownership'
  | 'orders'
  | 'order'
  | 'calls'
  | 'extensions'
  | 'messages'
  | 'guest-preview'
  | 'promotions'
  | 'menu'
  | 'tables'
  | 'staff'
  | 'my-profile'
  | 'shifts'
  | 'stats'
  | 'feedback'
  | 'settings'
  | 'subscription'
  | 'bookings'
  | 'chat'
  | 'support'

type Route = {
  name: RouteName
  orderId: number | null
  threadId: number | null
  previewOrigin?: 'settings' | 'venue'
  settingsSection?: VenueSettingsSection
  previewSection?: VenueSettingsSection
}

type NavRouteName = Exclude<RouteName, 'order' | 'more'>

type VenueShellRefs = {
  app: HTMLDivElement
  header: HTMLElement
  title: HTMLHeadingElement
  subtitle: HTMLParagraphElement
  nav: HTMLElement
  ownerNav: HTMLElement
  ownerPortfolio: HTMLElement
  moreButton: HTMLButtonElement
  moreUnreadBadge: HTMLSpanElement
  backButton: HTMLButtonElement
  accessState: HTMLParagraphElement
  venueSelect: HTMLSelectElement
  navButtons: Record<NavRouteName, HTMLButtonElement>
  messagesUnreadBadge: HTMLSpanElement
  navSections: Array<{ element: HTMLDivElement; buttons: HTMLButtonElement[] }>
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
    window.location.hash = '#/dashboard'
  }
}

function resolveRoute(): Route {
  const rawHash = window.location.hash.replace(/^#/, '')
  const cleaned = rawHash.startsWith('/') ? rawHash.slice(1) : rawHash
  if (!cleaned) {
    return { name: 'dashboard', orderId: null, threadId: null }
  }
  const [pathPart, queryPart = ''] = cleaned.split('?')
  const segments = pathPart.split('/').filter(Boolean)
  const route = segments[0] as RouteName | undefined
  if (
    !route ||
    ![
      'dashboard',
      'more',
      'ownership',
      'orders',
      'order',
      'calls',
      'extensions',
      'messages',
      'guest-preview',
      'promotions',
      'menu',
      'tables',
      'staff',
      'my-profile',
      'shifts',
      'stats',
      'feedback',
      'settings',
      'subscription',
      'bookings',
      'chat',
      'support'
    ].includes(route)
  ) {
    return { name: 'dashboard', orderId: null, threadId: null }
  }
  const query = new URLSearchParams(queryPart)
  if (route === 'order') {
    const orderId = parsePositiveInt(segments[1])
    return { name: 'order', orderId: orderId ?? null, threadId: null }
  }
  return {
    name: route,
    settingsSection: route === 'settings' ? segments[1] as VenueSettingsSection : undefined,
    previewSection: SETTINGS_SECTIONS.includes(query.get('section') as VenueSettingsSection) ? query.get('section') as VenueSettingsSection : undefined,
    orderId: null,
    threadId: parsePositiveInt(query.get('threadId')) ?? null,
    previewOrigin:
      route === 'guest-preview' && query.get('from') === 'settings'
        ? 'settings'
        : route === 'guest-preview'
          ? 'venue'
          : undefined
  }
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

function buildVenueShell(root: HTMLDivElement): VenueShellRefs {
  const app = el('div', { className: 'venue-shell' })
  const header = el('header', { className: 'venue-header' })
  const brand = el('div', { className: 'app-brand' })
  const title = el('h1', { text: 'Панель заведения' })
  const subtitle = el('p', { className: 'app-subtitle', text: 'Операционный режим' })
  append(brand, title, subtitle)

  const controls = el('div', { className: 'venue-controls' })
  const accessState = el('p', { className: 'venue-access-state', text: 'Загрузка доступа...' })
  const venueSelect = document.createElement('select')
  venueSelect.className = 'venue-select'
  venueSelect.disabled = true
  venueSelect.setAttribute('aria-label', 'Выбрать заведение')
  append(controls, accessState, venueSelect)

  const nav = el('nav', { className: 'venue-nav' })
  nav.setAttribute('aria-label', 'Разделы заведения')
  const navButtons = {
    dashboard: el('button', { className: 'nav-button', text: 'Обзор' }) as HTMLButtonElement,
    ownership: el('button', { className: 'nav-button', text: 'Мои заведения' }) as HTMLButtonElement,
    orders: el('button', { className: 'nav-button', text: 'Заказы' }) as HTMLButtonElement,
    bookings: el('button', { className: 'nav-button', text: 'Брони' }) as HTMLButtonElement,
    calls: el('button', { className: 'nav-button', text: 'Вызовы' }) as HTMLButtonElement,
    extensions: el('button', { className: 'nav-button', text: 'Запросы продления' }) as HTMLButtonElement,
    messages: el('button', { className: 'nav-button', text: 'Переписки' }) as HTMLButtonElement,
    'guest-preview': el('button', {
      className: 'nav-button',
      text: 'Предпросмотр для гостя'
    }) as HTMLButtonElement,
    promotions: el('button', { className: 'nav-button', text: 'Акции' }) as HTMLButtonElement,
    menu: el('button', { className: 'nav-button', text: 'Заказное меню' }) as HTMLButtonElement,
    tables: el('button', { className: 'nav-button', text: 'Столы и QR' }) as HTMLButtonElement,
    'my-profile': el('button', { className: 'nav-button', text: 'Моя карточка' }) as HTMLButtonElement,
    staff: el('button', { className: 'nav-button', text: 'Персонал' }) as HTMLButtonElement,
    shifts: el('button', { className: 'nav-button', text: 'График смен' }) as HTMLButtonElement,
    stats: el('button', { className: 'nav-button', text: 'Статистика' }) as HTMLButtonElement,
    feedback: el('button', { className: 'nav-button', text: 'Отзывы' }) as HTMLButtonElement,
    settings: el('button', { className: 'nav-button', text: 'Настройки' }) as HTMLButtonElement,
    subscription: el('button', { className: 'nav-button', text: 'Подписка' }) as HTMLButtonElement,
    chat: el('button', { className: 'nav-button', text: 'Чат персонала' }) as HTMLButtonElement,
    support: el('button', { className: 'nav-button', text: 'Поддержка' }) as HTMLButtonElement
  }
  Object.entries(navButtons).forEach(([route, button]) => {
    button.dataset.navRoute = route
  })
  const messagesUnreadBadge = el('span', {
    className: 'nav-unread-badge',
    text: ''
  }) as HTMLSpanElement
  messagesUnreadBadge.hidden = true
  messagesUnreadBadge.setAttribute('aria-hidden', 'true')
  navButtons.messages.append(' ', messagesUnreadBadge)

  const ownerPortfolio = el('div', { className: 'venue-nav-section' })
  ownerPortfolio.hidden = true
  append(ownerPortfolio, el('span', { className: 'venue-nav-section-title', text: 'Портфель' }))
  nav.appendChild(ownerPortfolio)

  const navSections = [
    {
      title: 'Работа смены',
      buttons: [
        navButtons.ownership,
        navButtons.dashboard,
        navButtons.orders,
        navButtons.bookings,
        navButtons.messages,
        navButtons.calls,
        navButtons.extensions,
        navButtons.shifts
      ]
    },
    {
      title: 'Настройки',
      buttons: [
        navButtons['guest-preview'],
        navButtons.menu,
        navButtons.promotions,
        navButtons.tables,
        navButtons.staff,
        navButtons.chat,
        navButtons.settings,
        navButtons.subscription
      ]
    },
    {
      title: 'Статистика',
      buttons: [navButtons.stats, navButtons.feedback]
    },
    {
      title: 'Помощь',
      buttons: [navButtons.support]
    }
  ].map((section) => {
    const group = el('div', { className: 'venue-nav-section' }) as HTMLDivElement
    const label = el('span', { className: 'venue-nav-section-title', text: section.title })
    const actions = el('div', { className: 'venue-nav-section-actions' })
    append(actions, ...section.buttons)
    append(group, label, actions)
    nav.appendChild(group)
    return { element: group, buttons: section.buttons }
  })

  const ownerNav = el('nav', { className: 'venue-owner-nav' })
  ownerNav.setAttribute('aria-label', 'Основная навигация')
  ownerNav.hidden = true
  const moreButton = el('button', { className: 'nav-button', text: 'Ещё' })
  moreButton.dataset.navRoute = 'more'
  const moreUnreadBadge = el('span', { className: 'nav-unread-badge' })
  moreUnreadBadge.hidden = true
  moreUnreadBadge.setAttribute('aria-hidden', 'true')
  moreButton.append(' ', moreUnreadBadge)
  const backButton = el('button', { className: 'venue-owner-back button-secondary', text: '← Назад' })
  backButton.hidden = true

  const errorCard = el('div', { className: 'error-card venue-error' }) as HTMLDivElement
  errorCard.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(errorCard, errorTitle, errorMessage, errorActions, errorDetails)

  const content = el('div', { className: 'container venue-content' })
  append(header, brand, controls, ownerNav, nav)
  append(app, header, errorCard, backButton, content)
  root.replaceChildren(app)

  return {
    app,
    header,
    title,
    subtitle,
    nav,
    ownerNav,
    ownerPortfolio,
    moreButton,
    moreUnreadBadge,
    backButton,
    accessState,
    venueSelect,
    navButtons,
    messagesUnreadBadge,
    navSections,
    content,
    errorCard,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function updateNav(navButtons: Record<NavRouteName, HTMLButtonElement>, active: RouteName) {
  Object.entries(navButtons).forEach(([key, button]) => {
    button.dataset.active = String(key === active)
    if (key === active) button.setAttribute('aria-current', 'page')
    else button.removeAttribute('aria-current')
  })
}

function maskErrorActions(presentation: ReturnType<typeof presentApiError>, retry: () => void): ApiErrorAction[] {
  if (presentation.actions.length) {
    return presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: retry }
      }
      return action
    })
  }
  return [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
}

function venueRoleLabel(role: VenueAccessDto['role']) {
  switch (role) {
    case 'OWNER':
      return 'владелец'
    case 'MANAGER':
      return 'менеджер'
    case 'STAFF':
      return 'персонал'
    default:
      return role
  }
}

function venueSelectorLabel(venue: VenueAccessDto): string {
  const base = venue.venueName?.trim() || `Заведение #${venue.venueId}`
  const meta = [venue.venueCity?.trim(), venueStatusLabel(venue.venueStatus)].filter(Boolean)
  return meta.length ? `${base} · ${meta.join(' · ')}` : base
}

function venueStatusLabel(status?: string | null): string | null {
  if (!status) return null
  switch (status.trim().toUpperCase()) {
    case 'PUBLISHED':
      return 'Опубликовано'
    case 'HIDDEN':
      return 'Скрыто'
    case 'DRAFT':
      return 'Черновик'
    case 'PAUSED':
      return 'На паузе'
    case 'SUSPENDED':
      return 'Заблокировано'
    case 'ARCHIVED':
      return 'Архив'
    case 'DELETED':
      return 'Удалено'
    default:
      return status
  }
}

export function mountVenueApp(options: VenueAppOptions) {
  const { root, backendUrl } = options
  if (!root) return () => undefined
  ensureDefaultHash()
  const isDebug = isDebugEnabled()
  const deps = buildApiDeps(isDebug)
  const refs = buildVenueShell(root)

  let disposed = false
  let accessAbort: AbortController | null = null
  let conversationUnreadAbort: AbortController | null = null
  let accessList: VenueAccessDto[] = []
  let selectedVenueId: number | null = null
  let currentRole: VenueAccessDto['role'] | null = null
  let currentPermissions: string[] = []
  let currentUserId: number | null = null
  let canLeaveScreen: ((url?: string) => boolean) | null = null
  let renderedUrl = window.location.href
  type SettingsDraft = {
    key: string
    venueId: number
    role: VenueAccessDto['role']
    userId: number | null
    section?: VenueSettingsSection
    root: HTMLDivElement
    state: VenueSettingsDraftState
    guard: () => boolean
    dispose: () => void
  }
  // Keep the actual editor (including raw/invalid input and pending explicit saves) in this
  // app session only. Identity includes permission scope; never reuse it across access changes.
  const drafts = new Map<string, SettingsDraft>()
  let currentSettings: SettingsDraft | null = null
  type ScheduleSession = { key: string; venueId: number; root: HTMLDivElement; controller: ScheduleNavigation; dispose: () => void }
  const scheduleSessions = new Map<string, ScheduleSession>()
  type PhotoSession = { key: string; venueId: number; route: 'staff' | 'my-profile'; root: HTMLElement; controller: StaffPhotoNavigation; dispose: () => void }
  const photoSessions = new Map<string, PhotoSession>()
  let currentPhotos: PhotoSession | null = null
  const photoKey = (access: VenueAccessDto, route: string) => JSON.stringify([currentUserId, access.venueId, access.role, [...access.permissions].sort(), access.teamScheduleModuleEnabled, route])
  let currentSchedule: ScheduleSession | null = null
  const scheduleKey = (access: VenueAccessDto) => JSON.stringify([currentUserId, access.venueId, access.role, [...access.permissions].sort(), access.teamScheduleModuleEnabled])
  const draftNotice = el('section', { className: 'card venue-draft-notice' })
  draftNotice.setAttribute('aria-label', 'Несохранённые черновики')
  draftNotice.hidden = true
  refs.content.before(draftNotice)
  const needsRetention = (draft: SettingsDraft) => draft.state.hasUnsavedChanges() || draft.state.isSaving()
  const updateDraftNotice = () => {
    if (disposed) return
    for (const [key, session] of photoSessions) {
      if (!session.controller.hasPending()) { photoSessions.delete(key); session.dispose() }
    }
    for (const [key, session] of scheduleSessions) {
      if (!session.controller.hasUnsavedChanges() && !session.controller.isSaving()) {
        scheduleSessions.delete(key)
        session.dispose()
      }
    }
    for (const [key, draft] of drafts) {
      if (!needsRetention(draft)) { drafts.delete(key); draft.dispose() }
    }
    const relevant = [...drafts.values(), ...(currentSettings ? [currentSettings] : [])]
      .filter((draft) => draft.venueId === selectedVenueId && draft.role === currentRole && draft.userId === currentUserId && needsRetention(draft))
    const retainedSchedules = [...scheduleSessions.values()].filter((draft) => draft.venueId === selectedVenueId && draft.controller.hasUnsavedChanges())
    const retainedPhotos = [...photoSessions.values()].filter((session) => session.venueId === selectedVenueId && session.controller.hasPending())
    draftNotice.hidden = relevant.length === 0 && retainedSchedules.length === 0 && retainedPhotos.length === 0
    draftNotice.replaceChildren()
    if (!relevant.length && !retainedSchedules.length && !retainedPhotos.length) return
    for (const session of retainedPhotos) {
      const link = el('a', { text: 'Вернуться к фотографии сотрудника' }); link.href = `#/${session.route}`
      append(draftNotice, el('p', { text: 'Выбранное фото и незавершённая операция сохранены в памяти этого окна. После возврата проверьте состояние.' }), link)
    }
    append(draftNotice, el('p', { text: 'Есть несохранённый черновик. Он хранится только в памяти этого окна. При закрытии или перезагрузке данные могут потеряться. На сервер они попадут только после нажатия «Сохранить».' }))
    for (const draft of retainedSchedules) {
      const link = el('a', { text: 'Вернуться к черновику смены' })
      link.href = '#/shifts'
      draftNotice.appendChild(link)
    }
    for (const draft of relevant) {
      if (draft === currentSettings) continue
      const link = el('a', { text: `Вернуться к черновику: ${draft.root.querySelector('h2')?.textContent ?? 'Настройки'}` })
      link.href = `#/settings${draft.section ? `/${draft.section}` : ''}`
      draftNotice.appendChild(link)
    }
  }
  // TS's bundled DOM lib predates Navigation API. Only cancellation and committed-entry
  // notifications are used; canIntercept is deliberately not treated as cancelable.
  type NavigationEvent = Event & { destination: { sameDocument: boolean; url: string } }
  const navigation = (window as Window & { navigation?: EventTarget }).navigation
  let conversationUnreadCount = 0

  const setConversationUnreadCount = (value: number) => {
    const count = Number.isSafeInteger(value) && value > 0 ? value : 0
    conversationUnreadCount = count
    const messageLink = refs.content.querySelector<HTMLElement>('[data-owner-messages]')
    if (messageLink) messageLink.setAttribute('aria-label', count > 0 ? `Переписки, непрочитанных: ${count}` : 'Переписки')
    let badge = messageLink?.querySelector<HTMLElement>('.nav-unread-badge')
    if (messageLink && !badge) {
      badge = el('span', { className: 'nav-unread-badge' })
      messageLink.appendChild(badge)
    }
    if (badge) { badge.hidden = count === 0; badge.textContent = count > 99 ? '99+' : String(count) }
    refs.moreUnreadBadge.hidden = true
    refs.moreUnreadBadge.textContent = count > 99 ? '99+' : String(count)
    refs.moreButton.setAttribute('aria-label', 'Ещё')
    refs.messagesUnreadBadge.hidden = count === 0
    refs.messagesUnreadBadge.textContent = count > 99 ? '99+' : String(count)
    refs.navButtons.messages.dataset.unreadCount = String(count)
    refs.navButtons.messages.setAttribute(
      'aria-label',
      count > 0 ? `Переписки, непрочитанных: ${count}` : 'Переписки'
    )
  }

  const updateAccessState = () => {
    const access = accessList.find((item) => item.venueId === selectedVenueId)
    const hasVenue = Boolean(access && currentRole)
    refs.app.classList.toggle('venue-owner-shell', hasVenue)
    refs.title.textContent = hasVenue ? access?.venueName?.trim() || `Заведение #${selectedVenueId}` : 'Панель заведения'
    refs.subtitle.textContent = hasVenue
      ? [access?.venueCity?.trim(), venueStatusLabel(access?.venueStatus) || 'Статус не указан'].filter(Boolean).join(' · ')
      : 'Операционный режим'
    refs.ownerNav.hidden = !hasVenue
    refs.ownerPortfolio.hidden = !hasVenue
    refs.navButtons.dashboard.textContent = 'Главная'
    refs.navSections.forEach((section) => {
      const actions = section.element.querySelector<HTMLElement>('.venue-nav-section-actions')!
      append(actions, ...section.buttons)
    })
    if (hasVenue) {
      refs.ownerPortfolio.appendChild(refs.navButtons.ownership)
      append(refs.ownerNav, refs.navButtons.dashboard, refs.navButtons.orders,
        currentRole === 'STAFF' ? refs.navButtons.shifts : refs.navButtons.settings, refs.moreButton)
    }
    refs.nav.hidden = hasVenue
    if (selectedVenueId && currentRole) {
      refs.accessState.textContent = `Роль: ${venueRoleLabel(currentRole)}`
    } else {
      refs.accessState.textContent = 'Выберите заведение'
    }
  }

  const clearAccessError = () => {
    refs.errorCard.hidden = true
    refs.errorActions.replaceChildren()
    refs.errorDetails.replaceChildren()
  }

  const showAccessError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.errorCard.hidden = false
    refs.errorCard.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    renderErrorActions(refs.errorActions, maskErrorActions(presentation, () => void loadAccess()))
    renderErrorDetails(refs.errorDetails, error, { isDebug })
  }

  const settingsDraftKey = (access: VenueAccessDto, section?: VenueSettingsSection) =>
    JSON.stringify([currentUserId, access.venueId, access.role, [...access.permissions].sort(), section ?? 'all'])

  const loadAccess = async () => {
    if (accessAbort) {
      accessAbort.abort()
    }
    const controller = new AbortController()
    accessAbort = controller
    refs.accessState.textContent = 'Загрузка доступа...'
    refs.venueSelect.disabled = true
    refs.venueSelect.replaceChildren()
    const result = await venueGetMe(backendUrl, deps, controller.signal)
    if (disposed || accessAbort !== controller) return
    accessAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) {
        return
      }
      showAccessError(result.error)
      return
    }
    clearAccessError()
    accessList = result.data.venues
    currentUserId = result.data.userId
    for (const [key, draft] of drafts) {
      const access = accessList.find((item) => item.venueId === draft.venueId)
      if (!access || key !== settingsDraftKey(access, draft.section)) { drafts.delete(key); draft.dispose() }
    }
    for (const [key, session] of scheduleSessions) {
      const access = accessList.find((item) => item.venueId === session.venueId)
      if (!access || key !== scheduleKey(access)) { scheduleSessions.delete(key); session.dispose() }
    }
    for (const [key, session] of photoSessions) {
      const access = accessList.find((item) => item.venueId === session.venueId)
      if (!access || key !== photoKey(access, session.route)) { photoSessions.delete(key); session.dispose() }
    }
    const telegramContext = getTelegramContext()
    const params = new URLSearchParams(window.location.search)
    const requestedVenueId = parsePositiveInt(params.get('venueId')) ?? parsePositiveInt(telegramContext.startParam)
    selectedVenueId = null
    if (requestedVenueId && accessList.some((venue) => venue.venueId === requestedVenueId)) {
      selectedVenueId = requestedVenueId
    } else if (accessList.length === 1) {
      selectedVenueId = accessList[0].venueId
    } else if (accessList.length > 0) {
      selectedVenueId = accessList[0].venueId
    }
    refs.venueSelect.replaceChildren()
    accessList.forEach((venue) => {
      const option = new Option(venueSelectorLabel(venue), String(venue.venueId))
      refs.venueSelect.appendChild(option)
    })
    if (selectedVenueId) {
      refs.venueSelect.value = String(selectedVenueId)
    }
    refs.venueSelect.hidden = accessList.length <= 1
    refs.venueSelect.disabled = accessList.length <= 1
    updateAccessFromSelection()
    persistSelectedVenue()
    render()
  }

  const persistSelectedVenue = () => {
    const nextUrl = new URL(window.location.href)
    const allowedSelection =
      selectedVenueId != null && accessList.some((venue) => venue.venueId === selectedVenueId)
        ? selectedVenueId
        : null
    if (allowedSelection) {
      nextUrl.searchParams.set('venueId', String(allowedSelection))
    } else {
      nextUrl.searchParams.delete('venueId')
    }
    window.history.replaceState(window.history.state, '', `${nextUrl.pathname}${nextUrl.search}${nextUrl.hash}`)
    renderedUrl = window.location.href
  }

  const updateAccessFromSelection = () => {
    const selection = parsePositiveInt(refs.venueSelect.value)
    selectedVenueId = selection
    const access = selection ? accessList.find((venue) => venue.venueId === selection) ?? null : null
    currentRole = access?.role ?? null
    currentPermissions = access?.permissions ?? []
    conversationUnreadAbort?.abort()
    conversationUnreadAbort = null
    setConversationUnreadCount(0)
    updateAccessState()
    updateNavVisibility()
  }

  const hasPermission = (permission: string) => currentPermissions.includes(permission)
  const canManagePublicCard = () => currentRole === 'OWNER' || currentRole === 'MANAGER'
  const selectedAccess = () =>
    selectedVenueId == null
      ? null
      : accessList.find((venue) => venue.venueId === selectedVenueId) ?? null
  const staffOwnScheduleModuleDisabled = () =>
    currentRole === 'STAFF' && selectedAccess()?.teamScheduleModuleEnabled === false

  const refreshConversationUnreadCount = async () => {
    const venueId = selectedVenueId
    const userId = currentUserId
    if (!venueId || !userId || !hasPermission('SUPPORT_MANAGE')) {
      conversationUnreadAbort?.abort()
      conversationUnreadAbort = null
      setConversationUnreadCount(0)
      return
    }
    conversationUnreadAbort?.abort()
    const controller = new AbortController()
    conversationUnreadAbort = controller
    const result = await venueGetConversationUnreadCount(backendUrl, { venueId }, deps, controller.signal)
    if (
      disposed ||
      conversationUnreadAbort !== controller ||
      selectedVenueId !== venueId ||
      currentUserId !== userId
    ) {
      return
    }
    conversationUnreadAbort = null
    if (!result.ok) {
      const link = refs.content.querySelector<HTMLElement>('[data-owner-messages]')
      if (link) {
        const badge = link.querySelector('.nav-unread-badge')
        if (badge) badge.textContent = '?'
        if (badge instanceof HTMLElement) badge.hidden = false
        link.setAttribute('aria-label', 'Переписки, число непрочитанных не удалось обновить')
      }
      return
    }
    setConversationUnreadCount(result.data.unreadCount)
  }

  const updateNavVisibility = () => {
    refs.navButtons.ownership.hidden = !accessList.some((access) => access.role === 'OWNER')
    refs.navButtons.orders.hidden = !hasPermission('ORDER_QUEUE_VIEW')
    refs.navButtons.bookings.hidden = !hasPermission('BOOKING_VIEW')
    refs.navButtons.messages.hidden = !hasPermission('SUPPORT_MANAGE')
    refs.navButtons.support.hidden = !hasPermission('SUPPORT_MANAGE')
    refs.navButtons.calls.hidden = !hasPermission('ORDER_QUEUE_VIEW')
    refs.navButtons.extensions.hidden = !hasPermission('SHIFT_EXTENSION_VIEW')
    refs.navButtons.menu.hidden = !hasPermission('MENU_VIEW')
    refs.navButtons['guest-preview'].hidden = currentRole !== 'OWNER' && currentRole !== 'MANAGER'
    refs.navButtons.promotions.hidden = currentRole !== 'OWNER' && currentRole !== 'MANAGER'
    refs.navButtons.tables.hidden = !hasPermission('TABLE_VIEW')
    refs.navButtons.staff.hidden = currentRole === 'STAFF'
    refs.navButtons.shifts.hidden =
      staffOwnScheduleModuleDisabled() ||
      (!hasPermission('STAFF_SCHEDULE_VIEW') && !hasPermission('STAFF_SCHEDULE_VIEW_OWN'))
    refs.navButtons.shifts.textContent = currentRole === 'STAFF' ? 'Мои смены' : 'График смен'
    refs.navButtons.stats.hidden = currentRole !== 'OWNER' && currentRole !== 'MANAGER'
    refs.navButtons.feedback.hidden = !hasPermission('FEEDBACK_VIEW')
    refs.navButtons.subscription.hidden = currentRole !== 'OWNER'
    refs.navButtons.chat.hidden = !hasPermission('STAFF_CHAT_LINK')
    refs.navButtons.settings.hidden =
      !canManagePublicCard() &&
      !hasPermission('SHIFT_EXTENSION_SETTINGS') &&
      !hasPermission('BOOKING_MANAGE') &&
      !hasPermission('STAFF_MODULE_SETTINGS_MANAGE')
    refs.navSections.forEach((section) => {
      section.element.hidden = section.buttons.every((button) => button.hidden || !section.element.contains(button))
    })
  }

  const canAccessRoute = (route: RouteName) => {
    switch (route) {
      case 'more':
        return currentRole !== null
      case 'ownership':
        return accessList.some((access) => access.role === 'OWNER')
      case 'orders':
      case 'order':
      case 'calls':
        return hasPermission('ORDER_QUEUE_VIEW')
      case 'extensions':
        return hasPermission('SHIFT_EXTENSION_VIEW')
      case 'bookings':
        return hasPermission('BOOKING_VIEW')
      case 'messages':
        return hasPermission('SUPPORT_MANAGE')
      case 'support':
        return hasPermission('SUPPORT_MANAGE')
      case 'menu':
        return hasPermission('MENU_VIEW')
      case 'guest-preview':
      case 'promotions':
        return currentRole === 'OWNER' || currentRole === 'MANAGER'
      case 'tables':
        return hasPermission('TABLE_VIEW')
      case 'settings':
        return (
          canManagePublicCard() ||
          hasPermission('SHIFT_EXTENSION_SETTINGS') ||
          hasPermission('BOOKING_MANAGE') ||
          hasPermission('STAFF_MODULE_SETTINGS_MANAGE')
        )
      case 'subscription':
        return currentRole === 'OWNER'
      case 'my-profile':
        return currentRole === 'STAFF' && hasPermission('STAFF_PROFILE_EDIT_OWN')
      case 'staff':
        return currentRole !== 'STAFF'
      case 'shifts':
        return (
          !staffOwnScheduleModuleDisabled() &&
          (hasPermission('STAFF_SCHEDULE_VIEW') || hasPermission('STAFF_SCHEDULE_VIEW_OWN'))
        )
      case 'stats':
        return currentRole === 'OWNER' || currentRole === 'MANAGER'
      case 'feedback':
        return hasPermission('FEEDBACK_VIEW')
      case 'chat':
        return hasPermission('STAFF_CHAT_LINK')
      default:
        return true
    }
  }

  const renderRouteContent = (route: Route) => {
    const screenRoot = document.createElement('div')
    screenRoot.className = 'screen-root'
    refs.content.replaceChildren(screenRoot)
    if (route.name === 'ownership') {
      if (!canAccessRoute(route.name)) {
        const denied = el('section', { className: 'card' })
        denied.setAttribute('role', 'alert')
        denied.setAttribute('aria-live', 'assertive')
        denied.setAttribute('aria-atomic', 'true')
        denied.tabIndex = -1
        append(
          denied,
          el('h2', { text: 'Недостаточно прав' }),
          el('p', { text: 'Раздел доступен только пользователю с действующей ролью OWNER.' })
        )
        refs.content.replaceChildren(denied)
        denied.focus({ preventScroll: true })
        return () => undefined
      }
      return renderVenueOwnershipScreen({
        root: screenRoot,
        backendUrl,
        isDebug,
        onOpenVenue: (venueId) => {
          const access = accessList.find((item) => item.venueId === venueId && item.role === 'OWNER')
          if (!access) return
          selectedVenueId = venueId
          refs.venueSelect.value = String(venueId)
          updateAccessFromSelection()
          persistSelectedVenue()
          navigate('#/dashboard')
        },
        onAccessRefresh: loadAccess
      })
    }
    const venueId = selectedVenueId
    const access =
      currentRole && venueId
        ? accessList.find((item) => item.venueId === venueId) ?? null
        : null
    if (!venueId || !access) {
      const placeholder = el('section', { className: 'card' })
      const text = venueId ? 'Недостаточно прав.' : 'Выберите заведение.'
      append(placeholder, el('h2', { text: 'Доступ' }), el('p', { text }))
      refs.content.replaceChildren(placeholder)
      return () => undefined
    }
    if (!canAccessRoute(route.name)) {
      const denied = el('section', { className: 'card' })
      const staffScheduleDisabled = route.name === 'shifts' && staffOwnScheduleModuleDisabled()
      append(
        denied,
        el('h2', { text: staffScheduleDisabled ? 'Мои смены недоступны' : 'Недостаточно прав' }),
        el('p', {
          text: staffScheduleDisabled
            ? 'Карточки команды и график смен отключены в настройках заведения.'
            : 'У вас нет доступа к этому разделу.'
        })
      )
      refs.content.replaceChildren(denied)
      return () => undefined
    }
    switch (route.name) {
      case 'more': {
        screenRoot.classList.add('venue-owner-more')
        append(screenRoot, el('h2', { text: 'Ещё' }))
        const links = el('div', { className: 'venue-settings-links' })
        for (const name of (currentRole === 'OWNER' ? ['ownership', 'stats', 'feedback', 'subscription', 'support'] : ['my-profile', 'ownership', 'tables', 'guest-preview', 'staff', 'chat', 'settings', 'stats', 'feedback', 'support']) as NavRouteName[]) {
          if (!canAccessRoute(name)) continue
          const link = el('a', { className: 'venue-owner-action', text: refs.navButtons[name].textContent ?? '' })
          link.href = `#/${name}`
          links.appendChild(link)
        }
        screenRoot.appendChild(links)
        return () => undefined
      }
      case 'dashboard':
        return renderVenueDashboardScreen({ root: screenRoot, backendUrl, isDebug, venueId, access, canAccessRoute })
      case 'orders':
        return renderVenueOrdersScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          onOpenOrder: (orderId) => navigate(`#/order/${orderId}`)
        })
      case 'order':
        return renderVenueOrderDetailScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          orderId: route.orderId,
          access,
          onBack: () => navigate('#/orders')
        })
      case 'calls':
        return renderVenueCallsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'extensions':
        return renderVenueShiftExtensionsScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          onOpenOrder: (orderId) => navigate(`#/order/${orderId}`)
        })
      case 'bookings':
        return renderVenueBookingsScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          onConversationUnreadChanged: () => void refreshConversationUnreadCount()
        })
      case 'messages':
        return renderVenueMessagesScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          screenMode: 'bookingMessages',
          initialThreadId: route.threadId,
          onConversationUnreadChanged: () => void refreshConversationUnreadCount()
        })
      case 'guest-preview':
        return renderVenueGuestPreviewScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          origin: route.previewOrigin ?? 'venue',
          onBack: () =>
            navigate(route.previewOrigin === 'settings' ? `#/settings${route.previewSection ? `/${route.previewSection}` : ''}` : '#/dashboard')
        })
      case 'menu':
        return renderVenueMenuScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'promotions':
        return renderVenuePromotionsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'tables':
        return renderVenueTablesScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'my-profile':
      case 'staff': {
        const key = photoKey(access, route.name)
        const retained = photoSessions.get(key)
        if (retained) {
          photoSessions.delete(key); currentPhotos = retained
          refs.content.replaceChildren(retained.root)
          canLeaveScreen = retained.controller.guard
          retained.controller.refresh()
          return retained.dispose
        }
        let controller: StaffPhotoNavigation = { guard: () => true, hasPending: () => false, refresh: () => undefined }
        const setPhotoNavigation = (value: StaffPhotoNavigation) => { controller = value; canLeaveScreen = value.guard }
        const dispose = route.name === 'my-profile'
          ? renderOwnStaffPhotoScreen(screenRoot, backendUrl, isDebug, venueId, setPhotoNavigation, updateDraftNotice)
          : renderVenueStaffScreen({ root: screenRoot, backendUrl, isDebug, venueId, access,
              currentUserId: currentUserId ?? 0, setPhotoNavigation, onPhotoChange: updateDraftNotice,
              onOpenStaffModuleSettings: () => navigate('#/settings/team') })
        currentPhotos = { key, venueId, route: route.name, root: screenRoot, controller, dispose }
        return dispose
      }
      case 'shifts': {
        const key = scheduleKey(access)
        const retained = scheduleSessions.get(key)
        if (retained) {
          scheduleSessions.delete(key)
          currentSchedule = retained
          refs.content.replaceChildren(retained.root)
          canLeaveScreen = retained.controller.guard
          retained.controller.applyRoute()
          return retained.dispose
        }
        const panelSession = crypto.randomUUID()
        const panelEntries = new Map<string, { depth: number; url: string; navigationKey?: string }>()
        const navigationKey = () => (window as Window & { navigation?: { currentEntry?: { key?: string } } }).navigation?.currentEntry?.key
        let exitingEntry: string | null = null
        let controller: ScheduleNavigation | null = null
        const dispose = renderVenueStaffScheduleScreen({
          root: screenRoot, backendUrl, isDebug, venueId, access,
          setNavigation: (value) => {
            controller = { ...value, applyRoute: () => {
              // A completed traversal must not suppress a later Forward to the expired panel.
              if (window.history.state?.hookahSchedulePanel?.id !== exitingEntry) exitingEntry = null
              value.applyRoute()
            } }
            canLeaveScreen = value.guard
          },
          onDraftChange: () => queueMicrotask(updateDraftNotice),
          navigatePanel: (hash, replace) => {
            if (disposed || currentSchedule?.root !== screenRoot || !screenRoot.isConnected) return
            const marker = window.history.state?.hookahSchedulePanel
            const entry = marker?.session === panelSession ? panelEntries.get(marker.id) : undefined
            const owned = entry && entry.url === window.location.href && (!entry.navigationKey || entry.navigationKey === navigationKey()) ? entry : undefined
            if (replace) {
              if (hash === '#/shifts' && owned) {
                if (exitingEntry !== marker.id) {
                  exitingEntry = marker.id
                  // Only traverse entries created by this live editor. Unknown history is never repaired.
                  window.history.go(-owned.depth)
                }
              } else {
                window.history.replaceState(window.history.state, '', hash)
                renderedUrl = window.location.href
              }
            } else if (window.location.hash !== hash) {
              if (canLeaveScreen && !canLeaveScreen(new URL(hash, window.location.href).href)) return
              const id = crypto.randomUUID()
              const state = window.history.state
              // Keep the original root entry and unrelated state intact; mark only our new panels.
              window.history.pushState({ ...(state && typeof state === 'object' ? state : {}), hookahSchedulePanel: { session: panelSession, id } }, '', hash)
              panelEntries.set(id, { depth: (owned?.depth ?? 0) + 1, url: window.location.href, navigationKey: navigationKey() })
              exitingEntry = null
              onHashChange()
            }
          },
          onOpenStaffModuleSettings: () => navigate('#/settings/team')
        })
        if (controller) {
          const activeController = controller as ScheduleNavigation
          currentSchedule = { key, venueId, root: screenRoot, controller: activeController, dispose }
          activeController.applyRoute()
        }
        return dispose
      }
      case 'stats':
        return renderVenueStatsScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'feedback':
        return renderVenueFeedbackScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'settings':
        if (!route.settingsSection) return renderVenueSettingsCatalog(screenRoot, access)
        if (route.settingsSection && !SETTINGS_SECTIONS.includes(route.settingsSection)) {
          append(screenRoot, el('h2', { text: 'Раздел не найден' }))
          return () => undefined
        }
        const key = settingsDraftKey(access, route.settingsSection)
        const retained = drafts.get(key)
        if (retained) {
          drafts.delete(key)
          currentSettings = retained
          canLeaveScreen = retained.guard
          refs.content.replaceChildren(retained.root)
          return retained.dispose
        }
        let state: VenueSettingsDraftState | null = null
        let guard = () => true
        const dispose = renderVenueSettingsScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          section: route.settingsSection,
          setLeaveGuard: (value) => { guard = value; canLeaveScreen = value },
          setDraftState: (value) => { state = value },
          onDraftChange: updateDraftNotice,
          onBack: () => navigate('#/dashboard'),
          onOpenPreview: () => navigate(`#/guest-preview?from=settings${route.settingsSection ? `&section=${route.settingsSection}` : ''}`),
          onStaffModuleSettingsSaved: (settings) => {
            if (disposed) return
            const index = accessList.findIndex((item) => item.venueId === venueId)
            if (index < 0) return
            accessList[index] = {
              ...accessList[index],
              teamScheduleModuleEnabled: settings.teamScheduleModuleEnabled
            }
            if (selectedVenueId === venueId) updateNavVisibility()
          }
        })
        if (state) currentSettings = { key, venueId, userId: currentUserId, role: access.role, section: route.settingsSection, root: screenRoot, state, guard, dispose }
        return dispose
      case 'subscription':
        return renderVenueSubscriptionScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'chat':
        return renderVenueChatLinkScreen({ root: screenRoot, backendUrl, isDebug, venueId, access })
      case 'support':
        return renderVenueMessagesScreen({
          root: screenRoot,
          backendUrl,
          isDebug,
          venueId,
          access,
          screenMode: 'supportTickets'
        })
      default:
        return () => undefined
    }
  }

  const navigate = (hash: string) => {
    if (window.location.hash !== hash && (!canLeaveScreen || canLeaveScreen(new URL(hash, window.location.href).href))) {
      window.location.hash = hash
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.moreButton, 'click', () => navigate('#/more')))
  disposables.push(on(refs.backButton, 'click', () => router.back()))
  disposables.push(on(refs.navButtons.dashboard, 'click', () => navigate('#/dashboard')))
  disposables.push(on(refs.navButtons.ownership, 'click', () => navigate('#/ownership')))
  disposables.push(on(refs.navButtons.orders, 'click', () => navigate('#/orders')))
  disposables.push(on(refs.navButtons.bookings, 'click', () => navigate('#/bookings')))
  disposables.push(on(refs.navButtons.messages, 'click', () => navigate('#/messages')))
  disposables.push(on(refs.navButtons.calls, 'click', () => navigate('#/calls')))
  disposables.push(on(refs.navButtons.extensions, 'click', () => navigate('#/extensions')))
  disposables.push(on(refs.navButtons['guest-preview'], 'click', () => navigate('#/guest-preview')))
  disposables.push(on(refs.navButtons.menu, 'click', () => navigate('#/menu')))
  disposables.push(on(refs.navButtons.promotions, 'click', () => navigate('#/promotions')))
  disposables.push(on(refs.navButtons.tables, 'click', () => navigate('#/tables')))
  disposables.push(on(refs.navButtons.staff, 'click', () => navigate('#/staff')))
  disposables.push(on(refs.navButtons.shifts, 'click', () => navigate('#/shifts')))
  disposables.push(on(refs.navButtons.stats, 'click', () => navigate('#/stats')))
  disposables.push(on(refs.navButtons.feedback, 'click', () => navigate('#/feedback')))
  disposables.push(on(refs.navButtons.settings, 'click', () => navigate('#/settings')))
  disposables.push(on(refs.navButtons.subscription, 'click', () => navigate('#/subscription')))
  disposables.push(on(refs.navButtons.chat, 'click', () => navigate('#/chat')))
  disposables.push(on(refs.navButtons.support, 'click', () => navigate('#/support')))
  disposables.push(
    on(refs.venueSelect, 'change', () => {
      if (canLeaveScreen && !canLeaveScreen()) {
        refs.venueSelect.value = String(selectedVenueId)
        return
      }
      updateAccessFromSelection()
      persistSelectedVenue()
      render()
    })
  )

  let currentDispose: (() => void) | null = null
  let currentRoute = resolveRoute()
  let ownerSectionOrigin = ['ownership', 'stats', 'feedback', 'subscription', 'support'].includes(currentRoute.name) ? 'more'
    : ['staff', 'chat'].includes(currentRoute.name) ? 'settings/team'
      : ['menu', 'tables', 'promotions'].includes(currentRoute.name) ? 'settings' : 'dashboard'

  const routeListeners = new Set<() => void>()
  const notifyRouteChange = () => {
    routeListeners.forEach((listener) => listener())
  }

  const router = {
    getRouteName: () => (currentRoute.name === 'dashboard' ? 'catalog' : currentRoute.name),
    navigate,
    back: () => {
      if (currentSchedule?.controller.back()) return
      if (currentRoute.name === 'order') {
        navigate(canAccessRoute('orders') ? '#/orders' : '#/dashboard')
        return
      }
      if (currentRoute.name === 'guest-preview' && currentRoute.previewOrigin === 'settings') {
        navigate(canAccessRoute('settings') ? `#/settings${currentRoute.previewSection ? `/${currentRoute.previewSection}` : ''}` : '#/more')
        return
      }
      if (currentRole !== null && currentRoute.name === 'settings' && currentRoute.settingsSection) {
        navigate(canAccessRoute('settings') ? '#/settings' : '#/more')
        return
      }
      if (currentRole !== null && !['dashboard', 'more', 'orders', 'settings', 'ownership'].includes(currentRoute.name)) {
        const parent = ownerSectionOrigin.split('/')[0] as RouteName
        navigate(`#/${canAccessRoute(parent) && parent !== currentRoute.name ? ownerSectionOrigin : 'more'}`)
        return
      }
      navigate('#/dashboard')
    },
    canGoBack: () => currentRoute.name !== 'dashboard',
    subscribe: (handler: () => void) => {
      routeListeners.add(handler)
      return () => routeListeners.delete(handler)
    }
  }

  const unbindBackButton = bindTelegramBackButton(router)

  const render = () => {
    const route = resolveRoute()
    const previousRoute = currentRoute
    currentRoute = route
    renderedUrl = window.location.href
    updateNav(refs.navButtons, route.name === 'order' ? 'orders' : route.name)
    const owner = currentRole !== null
    const routeChanged = previousRoute.name !== route.name || previousRoute.settingsSection !== route.settingsSection
    if (owner && previousRoute.name !== route.name && (['dashboard', 'more', 'settings'].includes(previousRoute.name))) {
      ownerSectionOrigin = previousRoute.name === 'settings' && previousRoute.settingsSection ? `settings/${previousRoute.settingsSection}` : previousRoute.name
    }
    const settingsActive = currentRole !== 'STAFF' && ['settings', 'menu', 'tables', 'staff', 'chat', 'promotions', 'guest-preview'].includes(route.name) && !(route.name === 'menu' && ownerSectionOrigin === 'dashboard')
    if (owner && settingsActive) refs.navButtons.settings.setAttribute('aria-current', 'page')
    if (owner && settingsActive) refs.navButtons.settings.dataset.active = 'true'
    const homeActive = ['dashboard', 'bookings', 'calls', 'messages', 'extensions', 'shifts'].includes(route.name) || (route.name === 'menu' && !settingsActive)
    if (owner && homeActive && !(currentRole === 'STAFF' && route.name === 'shifts')) {
      refs.navButtons.dashboard.setAttribute('aria-current', 'page')
      refs.navButtons.dashboard.dataset.active = 'true'
    }
    const moreActive = ['more', 'ownership', 'stats', 'feedback', 'subscription', 'support'].includes(route.name) || (currentRole === 'STAFF' && ['my-profile', 'tables', 'settings', 'chat'].includes(route.name))
    refs.moreButton.dataset.active = String(moreActive)
    if (moreActive) refs.moreButton.setAttribute('aria-current', 'page')
    else refs.moreButton.removeAttribute('aria-current')
    refs.backButton.hidden = true
    // The common shell retains the existing permission-filtered route contracts.
    refs.header.appendChild(refs.nav)
    refs.nav.hidden = owner
    const scheduleAccess = selectedAccess()
    if (currentSchedule && scheduleAccess && currentSchedule.key === scheduleKey(scheduleAccess) && route.name === 'shifts') {
      currentSchedule.controller.applyRoute()
      notifyRouteChange()
      return
    }
    if (currentSchedule) {
      const session = currentSchedule
      const access = accessList.find((item) => item.venueId === session.venueId)
      if (access && session.key === scheduleKey(access) && (session.controller.hasUnsavedChanges() || session.controller.isSaving())) {
        scheduleSessions.set(session.key, session)
        currentDispose = null
      }
      currentSchedule = null
    }
    if (currentPhotos) {
      const session = currentPhotos
      const access = accessList.find((item) => item.venueId === session.venueId)
      if (access && session.key === photoKey(access, session.route) && session.controller.hasPending()) {
        photoSessions.set(session.key, session)
        currentDispose = null
      }
      currentPhotos = null
    }
    const retainedAccess = currentSettings && accessList.find((item) => item.venueId === currentSettings?.venueId)
    if (currentSettings && retainedAccess && currentSettings.key === settingsDraftKey(retainedAccess, currentSettings.section) && needsRetention(currentSettings)) {
      drafts.set(currentSettings.key, currentSettings)
    } else currentDispose?.()
    currentSettings = null
    canLeaveScreen = null
    currentDispose = renderRouteContent(route)
    updateDraftNotice()
    setConversationUnreadCount(conversationUnreadCount)
    if (owner && !['dashboard', 'orders', 'order', 'more', 'ownership', 'guest-preview'].includes(route.name) && (route.name !== 'settings' || route.settingsSection)) {
      const back = el('button', { className: 'button-secondary', text: route.name === 'settings' && route.settingsSection && canAccessRoute('settings') ? '← Настройки' : '← Назад' })
      back.addEventListener('click', () => router.back())
      refs.content.prepend(back)
    }
    void refreshConversationUnreadCount()
    if ((owner && routeChanged) || (previousRoute.name === 'ownership' && route.name === 'dashboard')) {
      if (owner) window.scrollTo(0, 0)
      const heading = owner && route.name === 'dashboard' ? refs.title : refs.content.querySelector<HTMLElement>('h2')
      if (heading && !refs.content.querySelector('[role="alert"]')) {
        heading.tabIndex = -1
        heading.focus({ preventScroll: true })
      }
    }
  }

  const onHashChange = () => {
    if (window.location.href === renderedUrl) return
    // popstate/hashchange are notifications, not cancellation points. Follow the actual URL
    // even for unknown entries or overlapping traversals; retain dirty editors before unmount.
    const venueId = parsePositiveInt(new URLSearchParams(window.location.search).get('venueId'))
    if (venueId !== selectedVenueId) {
      refs.venueSelect.value = venueId && accessList.some((item) => item.venueId === venueId) ? String(venueId) : ''
      updateAccessFromSelection()
    }
    render()
    notifyRouteChange()
  }
  const onNavigate = (event: Event) => {
    const next = event as NavigationEvent
    if (next.destination.sameDocument && next.destination.url !== window.location.href && event.cancelable && canLeaveScreen && !canLeaveScreen(next.destination.url)) {
      event.preventDefault()
    }
  }
  const onEntryChange = () => {
    // replaceState during venue selection is synchronous; let the caller finish first.
    queueMicrotask(() => { if (!disposed) onHashChange() })
  }
  const beforeUnload = (event: BeforeUnloadEvent) => {
    if ((currentSettings && needsRetention(currentSettings)) || [...drafts.values()].some(needsRetention) || currentSchedule?.controller.hasUnsavedChanges() || currentSchedule?.controller.isSaving() || [...scheduleSessions.values()].some((session) => session.controller.hasUnsavedChanges() || session.controller.isSaving())) {
      event.preventDefault()
      event.returnValue = ''
    }
  }

  const onLinkClick = (event: MouseEvent) => {
    const link = (event.target as Element).closest<HTMLAnchorElement>('a[href^="#"]')
    if (link && link.hash !== window.location.hash && canLeaveScreen && !canLeaveScreen(link.href)) event.preventDefault()
  }
  root.addEventListener('click', onLinkClick)
  navigation?.addEventListener('navigate', onNavigate)
  navigation?.addEventListener('currententrychange', onEntryChange)
  window.addEventListener('beforeunload', beforeUnload)
  window.addEventListener('popstate', onHashChange)
  window.addEventListener('hashchange', onHashChange)
  void loadAccess()
  render()
  notifyRouteChange()

  return () => {
    disposed = true
    accessAbort?.abort()
    accessAbort = null
    conversationUnreadAbort?.abort()
    conversationUnreadAbort = null
    root.removeEventListener('click', onLinkClick)
    navigation?.removeEventListener('navigate', onNavigate)
    navigation?.removeEventListener('currententrychange', onEntryChange)
    window.removeEventListener('beforeunload', beforeUnload)
    window.removeEventListener('popstate', onHashChange)
    window.removeEventListener('hashchange', onHashChange)
    currentDispose?.()
    drafts.forEach((draft) => draft.dispose())
    drafts.clear()
    scheduleSessions.forEach((session) => session.dispose())
    scheduleSessions.clear()
    photoSessions.forEach((session) => session.dispose())
    photoSessions.clear()
    unbindBackButton()
    disposables.forEach((dispose) => dispose())
  }
}
