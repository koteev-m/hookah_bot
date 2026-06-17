import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  guestGetSupportThread,
  guestGetSupportThreads,
  guestReopenSupportThread,
  guestResolveSupportThread,
  guestSendSupportThreadMessage
} from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportMessageDto, SupportThreadDto, SupportThreadFilter } from '../shared/api/supportDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type SupportOpenBotResult = { ok: true } | { ok: false; message: string }

type GuestSupportThreadsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  hasTableContext: boolean
  onBack: () => void
  onOpenBot: () => SupportOpenBotResult
  onOpenVenueStaffCall: () => void
}

type GuestSupportRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  activeButton: HTMLButtonElement
  resolvedButton: HTMLButtonElement
  list: HTMLDivElement
  detail: HTMLDivElement
  botMessage: HTMLParagraphElement
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
  if (thread.contextLabel) return thread.contextLabel
  const displayNumber = thread.booking?.displayNumber
  if (displayNumber) return `Бронь №${displayNumber}`
  if (thread.bookingId) return `Бронь #${thread.bookingId}`
  return thread.title
}

function statusLabel(status: string): string {
  switch (status.toUpperCase()) {
    case 'NEW':
      return 'Новое'
    case 'OPEN':
      return 'В работе'
    case 'WAITING_GUEST':
      return 'Ждём вас'
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
    const author = message.authorRole === 'GUEST' ? 'Вы' : message.authorRole === 'VENUE' ? 'Заведение' : 'Система'
    const row = el('p', {
      className: message.authorRole === 'GUEST' ? 'venue-order-meta' : 'venue-order-sub',
      text: `${author}, ${formatDateTime(message.createdAt)}: ${message.text}`
    })
    list.appendChild(row)
  })
}

function buildDom(root: HTMLDivElement, hasTableContext: boolean): GuestSupportRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Сообщения' })
  const body = el('p', {
    text: 'Здесь отображается переписка с заведениями по броням. Ответы также доступны в Telegram-боте.'
  })
  const tableHint = el('p', {
    className: 'venue-order-sub',
    text: hasTableContext
      ? 'Срочный вопрос по текущему столу быстрее решить через «Вызвать персонал».'
      : 'Если вы уже за столом, откройте заведение по QR и используйте «Вызвать персонал».'
  })
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
  append(wrapper, header, list, detail, botMessage)
  root.replaceChildren(wrapper)
  return { status, refreshButton, activeButton, resolvedButton, list, detail, botMessage }
}

export function renderGuestSupportThreadsScreen(options: GuestSupportThreadsOptions) {
  const { root, backendUrl, isDebug, hasTableContext, onBack, onOpenBot, onOpenVenueStaffCall } = options
  if (!root) return () => undefined
  const refs = buildDom(root, hasTableContext)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let abortController: AbortController | null = null
  let threads: SupportThreadDto[] = []
  let currentFilter: SupportThreadFilter = 'active'
  let selectedThreadId: number | null = null

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
      append(empty, el('p', { className: 'venue-empty', text: 'Сообщений пока нет.' }))
      renderFallbackActions(empty)
      refs.list.appendChild(empty)
      return
    }
    threads.forEach((thread) => {
      const card = el('section', { className: 'card venue-message-thread-card' })
      card.dataset.selected = String(thread.threadId === selectedThreadId)
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
      openButton.addEventListener('click', () => void loadThread(thread.threadId))
      append(card, title, venue, meta, preview, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    refs.refreshButton.disabled = true
    refs.refreshButton.textContent = 'Обновляем…'
    updateFilterButtons()
    const result = await guestGetSupportThreads(backendUrl, deps, controller.signal, { filter: currentFilter })
    if (disposed || abortController !== controller) return
    abortController = null
    refs.refreshButton.disabled = false
    refs.refreshButton.textContent = '🔄 Обновить'
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    refs.status.textContent = ''
    threads = result.data.items
    renderThreadList()
    const selectedStillVisible = selectedThreadId && threads.some((thread) => thread.threadId === selectedThreadId)
    if (threads.length && (!selectedStillVisible || refs.detail.childElementCount === 0)) {
      void loadThread(threads[0].threadId)
    } else if (!threads.length) {
      selectedThreadId = null
      refs.detail.replaceChildren()
    }
  }

  const loadThread = async (threadId: number) => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    const result = await guestGetSupportThread(backendUrl, threadId, deps, controller.signal)
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
    textarea.placeholder = 'Напишите ответ заведению.'
    textarea.maxLength = 1000
    textarea.rows = 4
    const status = el('p', { className: 'status', text: '' })
    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
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
      renderThreadDetail(result.data.thread, result.data.messages)
      refs.status.textContent =
        action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.'
      showToast(action === 'resolve' ? 'Переписка завершена.' : 'Переписка возобновлена.')
      void loadThreads()
    }

    resolveButton.addEventListener('click', () => void applyStatusChange('resolve', resolveButton))
    reopenButton.addEventListener('click', () => void applyStatusChange('reopen', reopenButton))

    submitButton.addEventListener('click', async () => {
      const text = textarea.value.trim()
      if (!text) {
        status.textContent = 'Введите сообщение.'
        textarea.focus()
        return
      }
      submitButton.disabled = true
      const result = await guestSendSupportThreadMessage(backendUrl, thread.threadId, { message: text }, deps)
      submitButton.disabled = false
      if (!result.ok) {
        renderApiError(status, result.error, isDebug)
        return
      }
      currentMessages = [...currentMessages, result.data.message]
      renderMessages(messagesList, currentMessages)
      textarea.value = ''
      status.textContent = 'Сообщение отправлено заведению.'
      showToast('Сообщение отправлено заведению.')
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
