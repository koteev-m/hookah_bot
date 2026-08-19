import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  guestCreateVenueChat,
  guestCreateSupportThread,
  guestGetCatalog,
  guestGetSupportThread,
  guestGetSupportThreads,
  guestReopenSupportThread,
  guestResolveSupportThread,
  guestSendSupportThreadMessage
} from '../shared/api/guestApi'
import type { CatalogVenueDto } from '../shared/api/guestDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  GuestThreadSurface,
  SupportMessageDto,
  SupportThreadCreateRequest,
  SupportThreadDto,
  SupportThreadFilter,
  SupportThreadType
} from '../shared/api/supportDtos'
import type { TableContextSnapshot } from '../shared/state/tableContext'
import { createBookingMessageAttempt } from '../shared/bookingMessageAttempt'
import {
  bookingThreadError,
  bookingThreadLoading,
  type BookingThreadReconciliationState
} from '../shared/bookingThreadReconciliation'
import { bookingDisplayLabel } from '../shared/ui/bookingLabel'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type SupportOpenBotResult = { ok: true } | { ok: false; message: string }

type GuestSupportThreadsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  screenMode: 'messages' | 'tickets'
  hasTableContext: boolean
  tableSnapshot: TableContextSnapshot
  onBack: () => void
  onOpenBot: () => SupportOpenBotResult
  onOpenVenueStaffCall: () => void
  initialThreadId?: number | null
  createVenueChatVenueId?: number | null
  prefillSupportVenueId?: number | null
}

type GuestSupportRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  activeButton: HTMLButtonElement
  resolvedButton: HTMLButtonElement
  categorySelect: HTMLSelectElement
  venueField: HTMLDivElement
  venueSelect: HTMLSelectElement
  createTextarea: HTMLTextAreaElement
  createButton: HTMLButtonElement
  list: HTMLDivElement
  detail: HTMLDivElement
  botMessage: HTMLParagraphElement
}

type GuestSupportScreenCopy = {
  title: string
  body: string
  emptyText: string
  showCreate: boolean
  threadTypes: SupportThreadType[]
  surface: GuestThreadSurface
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug ? `${error.message} (${error.code})` : error.message || 'Не удалось выполнить действие.'
}

function renderSupportMessage(message: HTMLParagraphElement, result: SupportOpenBotResult) {
  message.hidden = false
  message.textContent = result.ok ? 'Открываем чат с ботом.' : result.message
}

function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function threadTitle(thread: SupportThreadDto): string {
  if (thread.threadType === 'VENUE_CHAT') {
    return thread.venueName ? `Чат с ${thread.venueName}` : thread.title
  }
  if (thread.threadType === 'BOOKING_THREAD') {
    return bookingDisplayLabel({
      bookingId: thread.booking?.bookingId ?? thread.bookingId,
      displayNumber: thread.booking?.displayNumber,
      displayLabel: thread.booking?.displayLabel,
      scheduledAt: thread.booking?.scheduledAt,
      legacyLabel: thread.contextLabel || thread.title
    })
  }
  if (thread.contextLabel) return thread.contextLabel
  return thread.title
}

function statusLabel(status: string): string {
  switch (status.toUpperCase()) {
    case 'NEW':
      return 'Новый'
    case 'OPEN':
    case 'IN_PROGRESS':
      return 'В работе'
    case 'WAITING_USER':
      return 'Ждём ответа'
    case 'RESOLVED':
      return 'Решено'
    case 'CLOSED':
      return 'Закрыто'
    default:
      return status
  }
}

function previewText(thread: SupportThreadDto): string {
  const value = thread.lastMessagePreview?.trim()
  if (!value) return 'Сообщений пока нет.'
  return value.length > 120 ? `${value.slice(0, 117)}...` : value
}

function unreadCount(thread: SupportThreadDto): number {
  const value = thread.unreadCount ?? 0
  return Number.isFinite(value) && value > 0 ? value : 0
}

function isResolvedThread(thread: SupportThreadDto): boolean {
  return thread.status.toUpperCase() === 'RESOLVED'
}

function isClosedThread(thread: SupportThreadDto): boolean {
  return thread.status.toUpperCase() === 'CLOSED'
}

function renderMessages(list: HTMLDivElement, messages: SupportMessageDto[]) {
  list.replaceChildren()
  if (!messages.length) {
    list.appendChild(el('p', { className: 'venue-empty', text: 'Сообщений пока нет.' }))
    return
  }
  messages.forEach((message) => {
    const author =
      message.authorRole === 'GUEST'
        ? 'Вы'
        : message.authorRole === 'VENUE'
          ? 'Заведение'
          : message.authorRole === 'PLATFORM'
            ? 'Поддержка'
            : 'Система'
    const row = el('p', {
      className: message.authorRole === 'GUEST' ? 'venue-order-meta' : 'venue-order-sub',
      text: `${author}, ${formatDateTime(message.createdAt)}: ${message.text}`
    })
    list.appendChild(row)
  })
}

function screenCopy(screenMode: GuestSupportThreadsOptions['screenMode']): GuestSupportScreenCopy {
  if (screenMode === 'tickets') {
    return {
      title: 'Мои обращения',
      body: 'Здесь можно сообщить о проблеме и посмотреть статус обращений.',
      emptyText: 'У вас пока нет обращений.',
      showCreate: true,
      threadTypes: ['SUPPORT_TICKET'],
      surface: 'SUPPORT'
    }
  }
  return {
    title: 'Чаты',
    body: 'Здесь все ваши чаты с заведениями: вопросы, брони и другие переписки. Проблемы и жалобы находятся в разделе Помощь.',
    emptyText: 'Пока нет чатов. Вы можете задать вопрос заведению из каталога или карточки заведения.',
    showCreate: false,
    threadTypes: ['BOOKING_THREAD', 'VENUE_CHAT'],
    surface: 'CONVERSATIONS'
  }
}

function buildDom(root: HTMLDivElement, hasTableContext: boolean, copy: GuestSupportScreenCopy): GuestSupportRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: copy.title })
  const body = el('p', { text: copy.body })
  const tableHint = el('p', {
    className: 'venue-order-sub',
    text: hasTableContext
      ? 'Срочный вопрос по текущему столу быстрее решить через «Вызвать персонал».'
      : 'Если вы уже за столом, откройте заведение по QR и используйте «Вызвать персонал».'
  })
  const createCard = el('section', { className: 'card' })
  createCard.hidden = !copy.showCreate
  const createTitle = el('h3', { text: 'Сообщить о проблеме' })
  const categorySelect = document.createElement('select')
  categorySelect.className = 'venue-select'
  ;[
    ['ORDER_SERVICE', 'Проблема с заказом/обслуживанием'],
    ['MINIAPP_TECHNICAL', 'Mini App / техническая проблема'],
    ['BOOKING', 'Бронь'],
    ['OTHER', 'Другое']
  ].forEach(([value, label]) => {
    categorySelect.appendChild(new Option(label, value))
  })
  const venueField = el('div', { className: 'support-venue-field' }) as HTMLDivElement
  const venueLabel = el('p', { className: 'field-label', text: 'Заведение' })
  const venueSelect = document.createElement('select')
  venueSelect.className = 'venue-select'
  venueSelect.appendChild(new Option('Не связано с конкретным заведением', ''))
  append(venueField, venueLabel, venueSelect)
  const createTextarea = document.createElement('textarea')
  createTextarea.className = 'venue-textarea'
  createTextarea.placeholder = 'Опишите проблему. Для срочного вопроса по столу используйте вызов персонала.'
  createTextarea.maxLength = 1000
  createTextarea.rows = 4
  const createButton = el('button', { className: 'button-small', text: 'Создать обращение' }) as HTMLButtonElement
  append(createCard, createTitle, categorySelect, venueField, createTextarea, createButton)
  const status = el('p', { className: 'status', text: '' })
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  const filterActions = el('div', { className: 'message-filter-tabs' })
  const activeButton = el('button', { className: 'button-small', text: 'Активные' }) as HTMLButtonElement
  const resolvedButton = el('button', { className: 'button-small button-secondary', text: 'Завершённые' }) as HTMLButtonElement
  append(filterActions, activeButton, resolvedButton)
  append(header, title, body, tableHint, status, filterActions, refreshButton)

  const list = el('div', { className: 'venue-messages-list' })
  const detail = el('div', { className: 'venue-messages-detail' })
  const botMessage = el('p', { className: 'staff-message', text: '' })
  botMessage.hidden = true
  append(wrapper, header, createCard, list, detail, botMessage)
  root.replaceChildren(wrapper)
  return {
    status,
    refreshButton,
    activeButton,
    resolvedButton,
    categorySelect,
    venueField,
    venueSelect,
    createTextarea,
    createButton,
    list,
    detail,
    botMessage
  }
}

export function renderGuestSupportThreadsScreen(options: GuestSupportThreadsOptions) {
  const {
    root,
    backendUrl,
    isDebug,
    screenMode,
    hasTableContext,
    tableSnapshot,
    onBack,
    onOpenBot,
    onOpenVenueStaffCall,
    initialThreadId,
    createVenueChatVenueId,
    prefillSupportVenueId
  } = options
  if (!root) return () => undefined
  const copy = screenCopy(screenMode)
  const refs = buildDom(root, hasTableContext, copy)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let abortController: AbortController | null = null
  let threads: SupportThreadDto[] = []
  let venues: CatalogVenueDto[] = []
  let currentFilter: SupportThreadFilter = 'active'
  let selectedThreadId: number | null = initialThreadId ?? null
  let disposeThreadDetail: (() => void) | null = null
  let detailThread: SupportThreadDto | null = null
  let reconciliationState: BookingThreadReconciliationState = bookingThreadLoading()
  let initialInventoryPending = initialThreadId != null
  let bookingSendInFlightThreadId: number | null = null
  const bookingSendBusyMessage = 'Дождитесь завершения отправки перед обновлением или сменой переписки.'

  const showBookingSendBusy = () => {
    if (bookingSendInFlightThreadId == null) return false
    refs.status.textContent = bookingSendBusyMessage
    const status = refs.detail.querySelector<HTMLElement>('[data-booking-status]')
    if (status) status.textContent = bookingSendBusyMessage
    return true
  }

  const setBookingSendInFlight = (threadId: number | null) => {
    bookingSendInFlightThreadId = threadId
    const busy = threadId != null
    refs.refreshButton.disabled = busy
    refs.activeButton.disabled = busy
    refs.resolvedButton.disabled = busy
    refs.list.querySelectorAll<HTMLButtonElement>('.venue-message-thread-card button').forEach((button) => {
      button.disabled = busy
    })
    if (busy) {
      showBookingSendBusy()
    } else {
      if (refs.status.textContent === bookingSendBusyMessage) refs.status.textContent = ''
      const status = refs.detail.querySelector<HTMLElement>('[data-booking-status]')
      if (status?.textContent === bookingSendBusyMessage) status.textContent = ''
    }
  }

  const clearReconciliationError = () => {
    refs.detail.querySelector('.booking-thread-reconciliation-error')?.remove()
  }

  const blockBookingComposer = (message: string) => {
    if (detailThread?.threadType !== 'BOOKING_THREAD') return
    const textarea = refs.detail.querySelector<HTMLTextAreaElement>('[data-booking-composer]')
    const submitButton = refs.detail.querySelector<HTMLButtonElement>('[data-booking-send]')
    const status = refs.detail.querySelector<HTMLElement>('[data-booking-status]')
    if (textarea) textarea.disabled = true
    if (submitButton) submitButton.disabled = true
    if (status) status.textContent = message
  }

  const showReconciliationError = (message: string) => {
    clearReconciliationError()
    blockBookingComposer(message)
    const error = el('section', { className: 'card booking-thread-reconciliation-error' })
    error.appendChild(el('p', { className: 'status', text: message }))
    const refresh = el('button', { className: 'button-secondary', text: 'Обновить переписку' }) as HTMLButtonElement
    refresh.addEventListener('click', () => void loadThreads())
    error.appendChild(refresh)
    refs.detail.prepend(error)
  }

  const selectedVenueId = () => {
    const raw = refs.venueSelect.value
    if (!raw) return null
    const value = Number(raw)
    return Number.isFinite(value) && Number.isInteger(value) && value > 0 ? value : null
  }

  const updateVenuePicker = () => {
    const category = refs.categorySelect.value
    const venueRequired = !hasTableContext && (category === 'ORDER_SERVICE' || category === 'BOOKING')
    refs.venueField.hidden = hasTableContext
    refs.venueSelect.required = venueRequired
    if (venueRequired && !selectedVenueId()) {
      refs.venueSelect.setCustomValidity('Выберите заведение.')
    } else {
      refs.venueSelect.setCustomValidity('')
    }
  }

  const renderVenueOptions = () => {
    const selected = selectedVenueId() ?? prefillSupportVenueId ?? null
    refs.venueSelect.replaceChildren(new Option('Не связано с конкретным заведением', ''))
    venues.forEach((venue) => {
      refs.venueSelect.appendChild(new Option(venue.name, String(venue.id)))
    })
    if (selected && venues.some((venue) => venue.id === selected)) {
      refs.venueSelect.value = String(selected)
    }
    updateVenuePicker()
  }

  const loadVenuesForSupport = async () => {
    if (screenMode !== 'tickets' || hasTableContext) return
    const result = await guestGetCatalog(backendUrl, deps)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    venues = result.data.venues ?? []
    renderVenueOptions()
  }

  const createTicket = async () => {
    const text = refs.createTextarea.value.trim()
    if (!text) {
      refs.status.textContent = 'Введите сообщение.'
      refs.createTextarea.focus()
      return
    }
    const category = refs.categorySelect.value as SupportThreadCreateRequest['category']
    const venueId = selectedVenueId()
    if (!hasTableContext && (category === 'ORDER_SERVICE' || category === 'BOOKING') && !venueId) {
      refs.status.textContent = 'Выберите заведение для этого обращения.'
      refs.venueSelect.focus()
      return
    }
    const payload: SupportThreadCreateRequest = {
      category,
      message: text,
      venueId,
      tableToken: tableSnapshot.status === 'resolved' && tableSnapshot.tableSessionActive ? tableSnapshot.tableToken : null,
      tableSessionId:
        tableSnapshot.status === 'resolved' && tableSnapshot.tableSessionActive ? tableSnapshot.tableSessionId : null
    }
    refs.createButton.disabled = true
    const result = await guestCreateSupportThread(backendUrl, payload, deps)
    refs.createButton.disabled = false
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    refs.createTextarea.value = ''
    refs.status.textContent = 'Обращение создано.'
    showToast('Обращение создано.')
    currentFilter = 'active'
    selectedThreadId = result.data.thread.threadId
    await loadThreads()
    void loadThread(result.data.thread.threadId)
  }

  const openOrCreateVenueChat = async (venueId: number) => {
    refs.status.textContent = 'Открываем чат с заведением...'
    const result = await guestCreateVenueChat(backendUrl, { venueId }, deps)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      await loadThreads()
      return
    }
    refs.status.textContent = ''
    selectedThreadId = result.data.thread.threadId
    showThreadDetail(result.data.thread, result.data.messages)
    await loadThreads()
  }

  const updateFilterButtons = () => {
    refs.activeButton.dataset.active = String(currentFilter === 'active')
    refs.resolvedButton.dataset.active = String(currentFilter === 'resolved')
  }

  const renderFallbackActions = (container: HTMLElement) => {
    const actions = el('div', { className: 'venue-inline-actions' })
    const staffButton = hasTableContext
      ? (el('button', { className: 'button-secondary', text: 'К вызову персонала' }) as HTMLButtonElement)
      : null
    const botButton = el('button', { className: 'button-secondary', text: 'Открыть чат с ботом' }) as HTMLButtonElement
    const backButton = el('button', { text: hasTableContext ? 'К заведению' : 'К каталогу' }) as HTMLButtonElement
    append(actions, staffButton, botButton, backButton)
    container.appendChild(actions)
    disposables.push(
      ...(staffButton ? [on(staffButton, 'click', onOpenVenueStaffCall)] : []),
      on(botButton, 'click', () => renderSupportMessage(refs.botMessage, onOpenBot())),
      on(backButton, 'click', onBack)
    )
  }

  const renderThreadList = () => {
    refs.list.replaceChildren()
    if (!threads.length) {
      const empty = el('section', { className: 'card' })
      append(empty, el('p', { className: 'venue-empty', text: copy.emptyText }))
      if (screenMode === 'messages') {
        renderFallbackActions(empty)
      }
      refs.list.appendChild(empty)
      return
    }
    threads.forEach((thread) => {
      const card = el('section', { className: 'card venue-message-thread-card' })
      card.dataset.selected = String(thread.threadId === selectedThreadId)
      card.dataset.threadId = String(thread.threadId)
      card.dataset.threadType = thread.threadType
      card.dataset.unreadCount = String(unreadCount(thread))
      if (thread.bookingId != null) card.dataset.bookingId = String(thread.bookingId)
      const title = el('h3', { text: threadTitle(thread) })
      const venue = el('p', { className: 'venue-order-sub', text: thread.venueName || 'Заведение' })
      const meta = el('p', {
        className: 'venue-order-sub',
        text: `${statusLabel(thread.status)} · ${formatDateTime(thread.lastMessageAt || thread.createdAt)}`
      })
      const preview = el('p', { className: 'message-preview', text: previewText(thread) })
      const unread = unreadCount(thread)
      if (unread > 0) {
        card.appendChild(el('span', { className: 'menu-item-badge', text: `Новых: ${unread}` }))
      }
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.disabled = bookingSendInFlightThreadId != null
      openButton.addEventListener('click', () => void loadThread(thread.threadId, 'selection'))
      append(card, title, venue, meta, preview, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    if (showBookingSendBusy()) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    refs.refreshButton.disabled = true
    refs.refreshButton.textContent = 'Обновляем…'
    reconciliationState = bookingThreadLoading()
    clearReconciliationError()
    blockBookingComposer('Сверяем переписку…')
    updateFilterButtons()
    const result = await guestGetSupportThreads(backendUrl, deps, controller.signal, {
      filter: initialInventoryPending ? undefined : currentFilter,
      surface: copy.surface
    })
    if (disposed || abortController !== controller) return
    abortController = null
    refs.refreshButton.disabled = false
    refs.refreshButton.textContent = '🔄 Обновить'
    if (!result.ok) {
      reconciliationState = bookingThreadError()
      refs.status.textContent = 'Не удалось сверить список переписок. Отправка сообщений заблокирована.'
      refs.refreshButton.textContent = 'Обновить переписку'
      showReconciliationError('Не удалось сверить переписку. Черновик сохранён; повторите чтение.')
      return
    }
    refs.status.textContent = ''
    const inventoryItems = result.data.items
    if (initialInventoryPending && initialThreadId != null) {
      threads = inventoryItems
      renderThreadList()
      void loadThread(initialThreadId, 'initial')
      return
    } else {
      threads = inventoryItems
    }
    if (
      selectedThreadId != null &&
      detailThread?.threadId === selectedThreadId &&
      !threads.some((thread) => thread.threadId === selectedThreadId) &&
      (currentFilter === 'resolved'
        ? isResolvedThread(detailThread) || isClosedThread(detailThread)
        : !isResolvedThread(detailThread) && !isClosedThread(detailThread))
    ) {
      threads = [detailThread, ...threads]
    }
    renderThreadList()
    const selectedStillVisible = selectedThreadId && threads.some((thread) => thread.threadId === selectedThreadId)
    if (selectedThreadId && selectedStillVisible) {
      void loadThread(selectedThreadId)
    } else if (screenMode === 'messages' && threads.length && !detailThread) {
      void loadThread(threads[0].threadId)
    } else if (!threads.length || !selectedStillVisible) {
      selectedThreadId = null
      clearThreadDetail()
    }
  }

  const loadThread = async (
    threadId: number,
    source: 'initial' | 'selection' | 'refresh' = 'refresh'
  ) => {
    if (showBookingSendBusy()) return null
    const inventoryThread = threads.find((thread) => thread.threadId === threadId)
    const canLoadExactInitialTarget = source === 'initial' && initialInventoryPending && initialThreadId === threadId
    if (!inventoryThread && !canLoadExactInitialTarget) {
      reconciliationState = bookingThreadError()
      showReconciliationError('Переписка отсутствует в актуальном списке. Обновите переписку.')
      return null
    }
    const selectionSupersedesInitial = source === 'selection' && initialInventoryPending
    if (selectionSupersedesInitial) {
      initialInventoryPending = false
    }
    const switchingThread = selectedThreadId !== threadId
    const preservedDraft =
      !switchingThread && detailThread?.threadId === threadId && detailThread.threadType === 'BOOKING_THREAD'
        ? refs.detail.querySelector<HTMLTextAreaElement>('[data-booking-composer]')?.value ?? ''
        : ''
    if (switchingThread && refs.detail.childElementCount > 0) {
      clearThreadDetail()
    }
    selectedThreadId = threadId
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    reconciliationState = bookingThreadLoading()
    clearReconciliationError()
    if (detailThread?.threadId === threadId) {
      blockBookingComposer('Загружаем актуальные сообщения…')
    } else {
      const loading = el('section', { className: 'card' })
      loading.appendChild(el('p', { className: 'venue-order-sub', text: 'Загружаем переписку…' }))
      refs.detail.replaceChildren(loading)
    }
    const result = await guestGetSupportThread(backendUrl, threadId, copy.surface, deps, controller.signal)
    if (disposed || abortController !== controller) return null
    abortController = null
    if (!result.ok) {
      reconciliationState = bookingThreadError()
      showReconciliationError('Не удалось загрузить сообщения. Черновик сохранён; обновите переписку.')
      return null
    }
    const exactThread = result.data.thread
    if (
      exactThread.threadId !== threadId ||
      !copy.threadTypes.some((threadType) => threadType === exactThread.threadType) ||
      (exactThread.threadType === 'BOOKING_THREAD' &&
        (!Number.isSafeInteger(exactThread.bookingId) || (exactThread.bookingId ?? 0) <= 0)) ||
      (inventoryThread != null &&
        (exactThread.threadType !== inventoryThread.threadType ||
          exactThread.bookingId !== inventoryThread.bookingId ||
          exactThread.venueId !== inventoryThread.venueId))
    ) {
      reconciliationState = bookingThreadError()
      showReconciliationError('Состав переписки изменился. Обновите переписку перед ответом.')
      return null
    }
    if (canLoadExactInitialTarget || selectionSupersedesInitial) {
      currentFilter = isResolvedThread(exactThread) || isClosedThread(exactThread) ? 'resolved' : 'active'
      initialInventoryPending = false
      threads = threads.filter((thread) =>
        currentFilter === 'resolved'
          ? isResolvedThread(thread) || isClosedThread(thread)
          : !isResolvedThread(thread) && !isClosedThread(thread)
      )
      updateFilterButtons()
    }
    reconciliationState = { status: 'READY_WITH_THREAD', thread: exactThread }
    selectedThreadId = exactThread.threadId
    const exactInventoryIndex = threads.findIndex((thread) => thread.threadId === selectedThreadId)
    if (exactInventoryIndex >= 0) {
      threads = threads.map((thread) =>
        thread.threadId === selectedThreadId
          ? { ...exactThread, unreadCount: 0 }
          : thread
      )
    } else {
      threads = [{ ...exactThread, unreadCount: 0 }, ...threads]
    }
    renderThreadList()
    showThreadDetail(exactThread, result.data.messages, preservedDraft)
    return exactThread
  }

  const renderThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[], preservedDraft = '') => {
    const bookingMessage = thread.threadType === 'BOOKING_THREAD'
    const messageAttempt = createBookingMessageAttempt()
    let detailDisposed = false
    let sendController: AbortController | null = null
    let currentMessages = messages
    const resolved = isResolvedThread(thread)
    const closed = isClosedThread(thread)
    const card = el('section', { className: 'card' })
    const title = el('h3', { text: threadTitle(thread) })
    const meta = el('p', {
      className: 'venue-order-sub',
      text: thread.booking
        ? `${thread.venueName || 'Заведение'} · ${formatDateTime(thread.booking.scheduledAt)}`
        : thread.venueName || 'Заведение'
    })
    const messagesList = el('div', { className: 'venue-support-messages' })
    renderMessages(messagesList, currentMessages)
    const lifecycleBanner =
      resolved || closed
        ? el('p', {
            className: 'message-thread-banner',
            text: closed ? 'Переписка закрыта.' : 'Переписка завершена.'
          })
        : null
    const textarea = document.createElement('textarea')
    textarea.className = 'venue-textarea'
    textarea.placeholder = screenMode === 'tickets' ? 'Напишите ответ по обращению.' : 'Напишите ответ заведению.'
    textarea.maxLength = 1000
    textarea.rows = 4
    textarea.value = preservedDraft
    textarea.dataset.bookingComposer = String(bookingMessage)
    const status = el('p', { className: 'status', text: '' })
    status.dataset.bookingStatus = String(bookingMessage)
    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
    submitButton.dataset.bookingSend = String(bookingMessage)
    const resolveButton = el('button', { className: 'button-small button-secondary', text: 'Завершить переписку' }) as HTMLButtonElement
    const reopenButton = el('button', { className: 'button-small', text: 'Возобновить переписку' }) as HTMLButtonElement
    if (!resolved && !closed) {
      append(actions, submitButton, resolveButton)
    } else if (resolved) {
      append(actions, reopenButton)
    }
    append(card, title, meta, messagesList, lifecycleBanner, textarea, status, actions)
    refs.detail.replaceChildren(card)
    textarea.hidden = resolved || closed
    submitButton.hidden = resolved || closed

    const applyStatusChange = async (action: 'resolve' | 'reopen', button: HTMLButtonElement) => {
      if (showBookingSendBusy()) return
      button.disabled = true
      const result =
        action === 'resolve'
          ? await guestResolveSupportThread(backendUrl, thread.threadId, deps)
          : await guestReopenSupportThread(backendUrl, thread.threadId, deps)
      button.disabled = false
      if (!result.ok) {
        renderApiError(status, result.error, isDebug)
        return
      }
      currentFilter = action === 'resolve' ? 'resolved' : 'active'
      selectedThreadId = result.data.thread.threadId
      showThreadDetail(result.data.thread, result.data.messages)
      refs.status.textContent =
        action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.'
      showToast(action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.')
      void loadThreads()
    }

    resolveButton.addEventListener('click', () => void applyStatusChange('resolve', resolveButton))
    reopenButton.addEventListener('click', () => void applyStatusChange('reopen', reopenButton))

    const invalidateDraftAttempt = () => {
      if (bookingMessage) {
        messageAttempt.invalidate()
      }
    }
    textarea.addEventListener('input', invalidateDraftAttempt)

    submitButton.addEventListener('click', async () => {
      if (sendController || (bookingMessage && reconciliationState.status !== 'READY_WITH_THREAD')) return
      const text = textarea.value.trim()
      if (!text) {
        status.textContent = 'Введите сообщение.'
        textarea.focus()
        return
      }
      submitButton.disabled = true
      textarea.disabled = true
      resolveButton.disabled = true
      reopenButton.disabled = true
      const controller = new AbortController()
      sendController = controller
      if (bookingMessage) {
        setBookingSendInFlight(thread.threadId)
      }
      const payload = bookingMessage
        ? {
            message: text,
            clientMessageId: messageAttempt.clientMessageIdFor(text, {
              venueId: thread.venueId ?? null,
              threadId: thread.threadId,
              bookingId: thread.bookingId ?? null
            })
          }
        : { message: text }
      const result = await guestSendSupportThreadMessage(backendUrl, thread.threadId, payload, deps, controller.signal)
      if (detailDisposed || sendController !== controller) return
      sendController = null
      if (bookingMessage) {
        setBookingSendInFlight(null)
      }
      submitButton.disabled = false
      textarea.disabled = false
      resolveButton.disabled = false
      reopenButton.disabled = false
      if (!result.ok) {
        const code = normalizeErrorCode(result.error)
        if (bookingMessage && result.error.status === 409 && code === ApiErrorCodes.BOOKING_MESSAGE_IDEMPOTENCY_PAYLOAD_MISMATCH) {
          messageAttempt.invalidate()
          status.textContent = 'Текст сообщения отличается от предыдущей попытки. Проверьте его и отправьте ещё раз.'
        } else {
          renderApiError(status, result.error, isDebug)
          if (bookingMessage) {
            status.textContent = `${status.textContent} Текст сохранён: повторите отправку без изменений — дубликат не появится.`
          }
        }
        return
      }
      messageAttempt.invalidate()
      currentMessages = [...currentMessages, result.data.message]
      renderMessages(messagesList, currentMessages)
      textarea.value = ''
      status.textContent = screenMode === 'tickets' ? 'Сообщение добавлено к обращению.' : 'Сообщение отправлено заведению.'
      showToast(screenMode === 'tickets' ? 'Сообщение добавлено.' : 'Сообщение отправлено заведению.')
      if (bookingMessage) {
        threads = threads.map((candidate) =>
          candidate.threadId === result.data.thread.threadId ? result.data.thread : candidate
        )
        renderThreadList()
      } else {
        void loadThreads()
      }
    })

    return () => {
      detailDisposed = true
      sendController?.abort()
      sendController = null
      if (bookingMessage && bookingSendInFlightThreadId === thread.threadId) {
        setBookingSendInFlight(null)
      }
      messageAttempt.invalidate()
      textarea.removeEventListener('input', invalidateDraftAttempt)
    }
  }

  const clearThreadDetail = () => {
    disposeThreadDetail?.()
    disposeThreadDetail = null
    detailThread = null
    refs.detail.replaceChildren()
  }

  const showThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[], preservedDraft = '') => {
    if (showBookingSendBusy()) return
    disposeThreadDetail?.()
    detailThread = thread
    disposeThreadDetail = renderThreadDetail(thread, messages, preservedDraft)
  }

  const setFilter = (filter: SupportThreadFilter) => {
    if (showBookingSendBusy()) return
    if (currentFilter === filter) return
    currentFilter = filter
    selectedThreadId = null
    clearThreadDetail()
    void loadThreads()
  }

  updateFilterButtons()
  disposables.push(on(refs.refreshButton, 'click', () => void loadThreads()))
  disposables.push(on(refs.activeButton, 'click', () => setFilter('active')))
  disposables.push(on(refs.resolvedButton, 'click', () => setFilter('resolved')))
  disposables.push(on(refs.categorySelect, 'change', updateVenuePicker))
  disposables.push(on(refs.venueSelect, 'change', updateVenuePicker))
  disposables.push(on(refs.createButton, 'click', () => void createTicket()))
  updateVenuePicker()
  if (screenMode === 'messages' && createVenueChatVenueId) {
    void openOrCreateVenueChat(createVenueChatVenueId)
  } else {
    void loadThreads()
  }
  void loadVenuesForSupport()

  return () => {
    disposed = true
    abortController?.abort()
    clearThreadDetail()
    disposables.forEach((dispose) => dispose())
  }
}
