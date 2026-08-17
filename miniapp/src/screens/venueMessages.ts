import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueEscalateSupportThread,
  venueGetSupportThread,
  venueGetSupportThreads,
  venueReopenSupportThread,
  venueResolveSupportThread,
  venueSendSupportThreadMessage
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportMessageDto, SupportThreadDto, SupportThreadFilter, SupportThreadType } from '../shared/api/supportDtos'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { createBookingMessageAttempt } from '../shared/bookingMessageAttempt'
import {
  bookingThreadError,
  bookingThreadLoading,
  type BookingThreadReconciliationState
} from '../shared/bookingThreadReconciliation'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type VenueMessagesOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  screenMode: 'bookingMessages' | 'supportTickets'
  initialThreadId?: number | null
}

type VenueMessagesRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  activeButton: HTMLButtonElement
  resolvedButton: HTMLButtonElement
  list: HTMLDivElement
  detail: HTMLDivElement
}

type VenueMessagesCopy = {
  title: string
  hint: string
  emptyText: string
  threadTypes: SupportThreadType[]
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

function supportTitle(thread: SupportThreadDto): string {
  if (thread.contextLabel) return thread.contextLabel
  const displayNumber = thread.booking?.displayNumber
  if (displayNumber) return `Бронь №${displayNumber}`
  if (thread.bookingId) return `Бронь #${thread.bookingId}`
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
        ? 'Гость'
        : message.authorRole === 'VENUE'
          ? 'Заведение'
          : message.authorRole === 'PLATFORM'
            ? 'Платформа'
            : 'Система'
    const row = el('p', {
      className: message.authorRole === 'GUEST' ? 'venue-order-sub' : 'venue-order-meta',
      text: `${author}, ${formatDateTime(message.createdAt)}: ${message.text}`
    })
    list.appendChild(row)
  })
}

function guestDisplay(thread: SupportThreadDto): string {
  return thread.guestDisplayName?.trim() || 'Гость'
}

function screenCopy(screenMode: VenueMessagesOptions['screenMode']): VenueMessagesCopy {
  if (screenMode === 'supportTickets') {
    return {
      title: 'Обращения',
      hint: 'Очередь обращений гостей. Срочные вызовы стола остаются в разделе «Вызовы».',
      emptyText: 'Обращений пока нет.',
      threadTypes: ['SUPPORT_TICKET']
    }
  }
  return {
    title: 'Сообщения',
    hint: 'Переписка с гостями по броням и обычным вопросам. Обращения по проблемам находятся отдельно.',
    emptyText: 'Сообщений пока нет.',
    threadTypes: ['BOOKING_THREAD', 'VENUE_CHAT']
  }
}

function threadKindLabel(thread: SupportThreadDto): string {
  if (thread.threadType === 'BOOKING_THREAD') return 'Бронь'
  if (thread.threadType === 'VENUE_CHAT') return 'Чат'
  return 'Обращение'
}

function buildDom(root: HTMLDivElement, copy: VenueMessagesCopy): VenueMessagesRefs {
  const wrapper = el('div', { className: 'venue-messages-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: copy.title })
  const hint = el('p', {
    className: 'venue-order-sub',
    text: copy.hint
  })
  const status = el('p', { className: 'status', text: '' })
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  const filterActions = el('div', { className: 'message-filter-tabs' })
  const activeButton = el('button', { className: 'button-small', text: 'Активные' }) as HTMLButtonElement
  const resolvedButton = el('button', { className: 'button-small button-secondary', text: 'Завершённые' }) as HTMLButtonElement
  append(filterActions, activeButton, resolvedButton)
  append(header, title, hint, status, filterActions, refreshButton)
  const layout = el('div', { className: 'venue-messages-layout' })
  const list = el('div', { className: 'venue-messages-list' })
  const detail = el('div', { className: 'venue-messages-detail' })
  append(layout, list, detail)
  append(wrapper, header, layout)
  root.replaceChildren(wrapper)
  return { status, refreshButton, activeButton, resolvedButton, list, detail }
}

export function renderVenueMessagesScreen(options: VenueMessagesOptions) {
  const { root, backendUrl, isDebug, venueId, access, screenMode, initialThreadId } = options
  if (!root) return () => undefined
  const copy = screenCopy(screenMode)
  const refs = buildDom(root, copy)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let abortController: AbortController | null = null
  let threads: SupportThreadDto[] = []
  const canReply = access.permissions.includes('SUPPORT_MANAGE')
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

  const updateFilterButtons = () => {
    refs.activeButton.dataset.active = String(currentFilter === 'active')
    refs.resolvedButton.dataset.active = String(currentFilter === 'resolved')
  }

  const setLoading = (loading: boolean) => {
    refs.refreshButton.disabled = loading
    refs.refreshButton.textContent = loading ? 'Обновляем…' : '🔄 Обновить'
  }

  const renderThreadList = () => {
    refs.list.replaceChildren()
    if (!threads.length) {
      const empty = el('section', { className: 'card' })
      empty.appendChild(el('p', { className: 'venue-empty', text: copy.emptyText }))
      refs.list.appendChild(empty)
      return
    }
    threads.forEach((thread) => {
      const card = el('section', { className: 'card venue-message-thread-card' })
      card.dataset.selected = String(thread.threadId === selectedThreadId)
      const title = el('h3', { text: supportTitle(thread) })
      const guest = el('p', { className: 'venue-order-sub', text: `Гость: ${guestDisplay(thread)}` })
      const meta = el('p', {
        className: 'venue-order-sub',
        text: `${threadKindLabel(thread)} · ${statusLabel(thread.status)} · ${thread.assigneeScope === 'PLATFORM' ? 'Платформа' : 'Заведение'} · ${formatDateTime(thread.lastMessageAt || thread.createdAt)}`
      })
      const preview = el('p', { className: 'message-preview', text: previewText(thread) })
      const unread = unreadCount(thread)
      if (unread > 0) {
        card.appendChild(el('span', { className: 'menu-item-badge', text: `Новых: ${unread}` }))
      }
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.disabled = bookingSendInFlightThreadId != null
      openButton.addEventListener('click', () => void loadThread(thread.threadId, 'selection'))
      append(card, title, guest, meta, preview, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    if (showBookingSendBusy()) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    reconciliationState = bookingThreadLoading()
    clearReconciliationError()
    blockBookingComposer('Сверяем переписку…')
    updateFilterButtons()
    const result = await venueGetSupportThreads(
      backendUrl,
      {
        venueId,
        filter: initialInventoryPending ? undefined : currentFilter,
        threadTypes: copy.threadTypes
      },
      deps,
      controller.signal
    )
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
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
    } else if (!threads.length || !selectedStillVisible) {
      selectedThreadId = null
      clearThreadDetail()
    }
  }

  const loadThread = async (
    threadId: number,
    source: 'initial' | 'selection' | 'refresh' = 'refresh'
  ) => {
    if (showBookingSendBusy()) return
    const inventoryThread = threads.find((thread) => thread.threadId === threadId)
    const canLoadExactInitialTarget = source === 'initial' && initialInventoryPending && initialThreadId === threadId
    if (!inventoryThread && !canLoadExactInitialTarget) {
      reconciliationState = bookingThreadError()
      showReconciliationError('Переписка отсутствует в актуальном списке. Обновите переписку.')
      return
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
    const result = await venueGetSupportThread(backendUrl, { venueId, threadId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    if (!result.ok) {
      reconciliationState = bookingThreadError()
      showReconciliationError('Не удалось загрузить сообщения. Черновик сохранён; обновите переписку.')
      return
    }
    const exactThread = result.data.thread
    if (
      exactThread.threadId !== threadId ||
      exactThread.venueId !== venueId ||
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
      return
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
        thread.threadId === selectedThreadId ? { ...exactThread, unreadCount: 0 } : thread
      )
    } else {
      threads = [{ ...exactThread, unreadCount: 0 }, ...threads]
    }
    renderThreadList()
    showThreadDetail(exactThread, result.data.messages, preservedDraft)
  }

  const renderThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[], preservedDraft = '') => {
    const bookingMessage = thread.threadType === 'BOOKING_THREAD'
    const messageAttempt = createBookingMessageAttempt()
    let detailDisposed = false
    let sendController: AbortController | null = null
    const card = el('section', { className: 'card' })
    const title = el('h3', { text: supportTitle(thread) })
    const meta = el('p', {
      className: 'venue-order-sub',
      text: thread.booking
        ? `${guestDisplay(thread)} · ${formatDateTime(thread.booking.scheduledAt)} · гостей: ${thread.booking.partySize ?? '—'} · ${thread.booking.status ?? ''}`
        : `${guestDisplay(thread)} · ${thread.tableLabel ?? thread.orderDisplayLabel ?? thread.title} · ${thread.assigneeScope === 'PLATFORM' ? 'Передано платформе' : 'В работе заведения'}`
    })
    const resolved = isResolvedThread(thread)
    const closed = isClosedThread(thread)
    const platformScoped = thread.assigneeScope === 'PLATFORM'
    let currentMessages = messages
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
    textarea.placeholder = 'Напишите ответ гостю.'
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
    const escalateButton = el('button', { className: 'button-small button-secondary', text: 'Передать платформе' }) as HTMLButtonElement
    const transferConfirm = el('div', { className: 'error-card' })
    transferConfirm.hidden = true
    const transferConfirmTitle = el('h3', { text: 'Передать обращение платформе?' })
    const transferConfirmText = el('p', {
      text:
        'Используйте это, если проблема связана с Mini App, ботом, QR, оплатой, правами доступа или технической ошибкой. Владелец платформы увидит обращение и сможет ответить гостю.'
    })
    const transferConfirmActions = el('div', { className: 'error-actions' })
    const transferConfirmButton = el('button', { className: 'button-small', text: 'Передать платформе' }) as HTMLButtonElement
    const transferCancelButton = el('button', { className: 'button-small button-secondary', text: 'Отмена' }) as HTMLButtonElement
    append(transferConfirmActions, transferConfirmButton, transferCancelButton)
    append(transferConfirm, transferConfirmTitle, transferConfirmText, transferConfirmActions)
    const canTransferToPlatform = screenMode === 'supportTickets' && thread.threadType === 'SUPPORT_TICKET'
    if (canReply && !resolved && !closed && !platformScoped) {
      append(actions, submitButton, resolveButton, escalateButton)
      escalateButton.hidden = !canTransferToPlatform
    } else if (canReply && resolved) {
      append(actions, reopenButton)
    }
    append(card, title, meta, messagesList, lifecycleBanner, textarea, status, transferConfirm, actions)
    refs.detail.replaceChildren(card)

    submitButton.hidden = !canReply || resolved || closed || platformScoped
    textarea.hidden = !canReply || resolved || closed || platformScoped
    if (platformScoped && !resolved && !closed) {
      status.textContent = 'Обращение передано платформе. Ответы от заведения отключены.'
    }

    const applyStatusChange = async (action: 'resolve' | 'reopen', button: HTMLButtonElement) => {
      if (showBookingSendBusy()) return
      button.disabled = true
      const result =
        action === 'resolve'
          ? await venueResolveSupportThread(backendUrl, { venueId, threadId: thread.threadId }, deps)
          : await venueReopenSupportThread(backendUrl, { venueId, threadId: thread.threadId }, deps)
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
    escalateButton.addEventListener('click', () => {
      if (showBookingSendBusy()) return
      transferConfirm.hidden = false
    })
    transferCancelButton.addEventListener('click', () => {
      transferConfirm.hidden = true
    })
    transferConfirmButton.addEventListener('click', async () => {
      if (showBookingSendBusy()) return
      transferConfirmButton.disabled = true
      escalateButton.disabled = true
      const result = await venueEscalateSupportThread(backendUrl, { venueId, threadId: thread.threadId }, deps)
      transferConfirmButton.disabled = false
      escalateButton.disabled = false
      if (!result.ok) {
        renderApiError(status, result.error, isDebug)
        return
      }
      showThreadDetail(result.data.thread, result.data.messages)
      showToast('Обращение передано платформе.')
      void loadThreads()
    })

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
        status.textContent = 'Введите сообщение гостю.'
        textarea.focus()
        return
      }
      submitButton.disabled = true
      textarea.disabled = true
      resolveButton.disabled = true
      reopenButton.disabled = true
      escalateButton.disabled = true
      const controller = new AbortController()
      sendController = controller
      if (bookingMessage) {
        setBookingSendInFlight(thread.threadId)
      }
      const body = bookingMessage
        ? {
            message: text,
            clientMessageId: messageAttempt.clientMessageIdFor(text, {
              venueId,
              threadId: thread.threadId,
              bookingId: thread.bookingId ?? null
            })
          }
        : { message: text }
      const result = await venueSendSupportThreadMessage(
        backendUrl,
        { venueId, threadId: thread.threadId, body },
        deps,
        controller.signal
      )
      if (detailDisposed || sendController !== controller) return
      sendController = null
      if (bookingMessage) {
        setBookingSendInFlight(null)
      }
      submitButton.disabled = false
      textarea.disabled = false
      resolveButton.disabled = false
      reopenButton.disabled = false
      escalateButton.disabled = false
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
      textarea.value = ''
      currentMessages = [...currentMessages, result.data.message]
      renderMessages(messagesList, currentMessages)
      status.textContent = 'Сообщение отправлено гостю.'
      showToast('Сообщение отправлено гостю.')
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
  void loadThreads()

  return () => {
    disposed = true
    abortController?.abort()
    clearThreadDetail()
    disposables.forEach((dispose) => dispose())
  }
}
