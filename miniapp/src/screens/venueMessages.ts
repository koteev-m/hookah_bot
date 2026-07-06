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
import type { SupportMessageDto, SupportThreadDto, SupportThreadFilter } from '../shared/api/supportDtos'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type VenueMessagesOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
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

function buildDom(root: HTMLDivElement): VenueMessagesRefs {
  const wrapper = el('div', { className: 'venue-messages-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Сообщения' })
  const hint = el('p', {
    className: 'venue-order-sub',
    text: 'Очередь обращений гостей. Срочные вызовы стола остаются в разделе «Вызовы».'
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
  const { root, backendUrl, isDebug, venueId, access, initialThreadId } = options
  if (!root) return () => undefined
  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let abortController: AbortController | null = null
  let threads: SupportThreadDto[] = []
  const canReply = access.permissions.includes('SUPPORT_MANAGE')
  let currentFilter: SupportThreadFilter = 'active'
  let selectedThreadId: number | null = initialThreadId ?? null

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
      empty.appendChild(el('p', { className: 'venue-empty', text: 'Сообщений пока нет.' }))
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
        text: `${thread.threadType === 'BOOKING_THREAD' ? 'Бронь' : 'Обращение'} · ${statusLabel(thread.status)} · ${thread.assigneeScope === 'PLATFORM' ? 'Платформа' : 'Заведение'} · ${formatDateTime(thread.lastMessageAt || thread.createdAt)}`
      })
      const preview = el('p', { className: 'message-preview', text: previewText(thread) })
      const unread = unreadCount(thread)
      if (unread > 0) {
        card.appendChild(el('span', { className: 'menu-item-badge', text: `Новых: ${unread}` }))
      }
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.addEventListener('click', () => void loadThread(thread.threadId))
      append(card, title, guest, meta, preview, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    updateFilterButtons()
    const result = await venueGetSupportThreads(backendUrl, { venueId, filter: currentFilter }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    refs.status.textContent = ''
    threads = result.data.items
    renderThreadList()
    const selectedStillVisible = selectedThreadId && threads.some((thread) => thread.threadId === selectedThreadId)
    if (threads.length && (!selectedStillVisible || refs.detail.childElementCount === 0)) {
      const preferredThreadId =
        initialThreadId && threads.some((thread) => thread.threadId === initialThreadId)
          ? initialThreadId
          : threads[0].threadId
      void loadThread(preferredThreadId)
    } else if (!threads.length) {
      selectedThreadId = null
      refs.detail.replaceChildren()
    }
  }

  const loadThread = async (threadId: number) => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    const loading = el('section', { className: 'card' })
    loading.appendChild(el('p', { className: 'venue-order-sub', text: 'Загружаем переписку…' }))
    refs.detail.replaceChildren(loading)
    const result = await venueGetSupportThread(backendUrl, { venueId, threadId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    selectedThreadId = result.data.thread.threadId
    threads = threads.map((thread) =>
      thread.threadId === selectedThreadId ? { ...thread, unreadCount: 0, lastMessagePreview: result.data.thread.lastMessagePreview } : thread
    )
    renderThreadList()
    renderThreadDetail(result.data.thread, result.data.messages)
  }

  const renderThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[]) => {
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
    const status = el('p', { className: 'status', text: '' })
    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
    const resolveButton = el('button', { className: 'button-small button-secondary', text: 'Завершить переписку' }) as HTMLButtonElement
    const reopenButton = el('button', { className: 'button-small', text: 'Возобновить переписку' }) as HTMLButtonElement
    const escalateButton = el('button', { className: 'button-small button-secondary', text: 'Эскалировать' }) as HTMLButtonElement
    if (canReply && !resolved && !closed && !platformScoped) {
      append(actions, submitButton, resolveButton, escalateButton)
    } else if (canReply && resolved) {
      append(actions, reopenButton)
    }
    append(card, title, meta, messagesList, lifecycleBanner, textarea, status, actions)
    refs.detail.replaceChildren(card)

    submitButton.hidden = !canReply || resolved || closed || platformScoped
    textarea.hidden = !canReply || resolved || closed || platformScoped
    if (platformScoped && !resolved && !closed) {
      status.textContent = 'Обращение передано платформе. Ответы от заведения отключены.'
    }

    const applyStatusChange = async (action: 'resolve' | 'reopen', button: HTMLButtonElement) => {
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
      renderThreadDetail(result.data.thread, result.data.messages)
      refs.status.textContent =
        action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.'
      showToast(action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.')
      void loadThreads()
    }

    resolveButton.addEventListener('click', () => void applyStatusChange('resolve', resolveButton))
    reopenButton.addEventListener('click', () => void applyStatusChange('reopen', reopenButton))
    escalateButton.addEventListener('click', async () => {
      escalateButton.disabled = true
      const result = await venueEscalateSupportThread(backendUrl, { venueId, threadId: thread.threadId }, deps)
      escalateButton.disabled = false
      if (!result.ok) {
        renderApiError(status, result.error, isDebug)
        return
      }
      renderThreadDetail(result.data.thread, result.data.messages)
      showToast('Обращение передано платформе.')
      void loadThreads()
    })

    submitButton.addEventListener('click', async () => {
      const text = textarea.value.trim()
      if (!text) {
        status.textContent = 'Введите сообщение гостю.'
        textarea.focus()
        return
      }
      submitButton.disabled = true
      const result = await venueSendSupportThreadMessage(
        backendUrl,
        { venueId, threadId: thread.threadId, body: { message: text } },
        deps
      )
      submitButton.disabled = false
      if (!result.ok) {
        renderApiError(status, result.error, isDebug)
        return
      }
      textarea.value = ''
      currentMessages = [...currentMessages, result.data.message]
      renderMessages(messagesList, currentMessages)
      status.textContent = 'Сообщение отправлено гостю.'
      showToast('Сообщение отправлено гостю.')
      void loadThreads()
    })
  }

  const setFilter = (filter: SupportThreadFilter) => {
    if (currentFilter === filter) return
    currentFilter = filter
    selectedThreadId = null
    refs.detail.replaceChildren()
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
    disposables.forEach((dispose) => dispose())
  }
}
