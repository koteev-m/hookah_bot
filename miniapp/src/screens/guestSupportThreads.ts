import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestGetSupportThread, guestGetSupportThreads, guestSendSupportThreadMessage } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportMessageDto, SupportThreadDto } from '../shared/api/supportDtos'
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
  const displayNumber = thread.booking?.displayNumber
  if (displayNumber) return `Бронь №${displayNumber}`
  if (thread.bookingId) return `Бронь #${thread.bookingId}`
  return thread.title
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
  append(header, title, body, tableHint, status, refreshButton)

  const list = el('div', { className: 'venue-messages-list' })
  const detail = el('div', { className: 'venue-messages-detail' })
  const botMessage = el('p', { className: 'staff-message', text: '' })
  botMessage.hidden = true
  append(wrapper, header, list, detail, botMessage)
  root.replaceChildren(wrapper)
  return { status, refreshButton, list, detail, botMessage }
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
      const title = el('h3', { text: threadTitle(thread) })
      const venue = el('p', { className: 'venue-order-sub', text: thread.venueName || 'Заведение' })
      const meta = el('p', {
        className: 'venue-order-sub',
        text: formatDateTime(thread.lastMessageAt || thread.createdAt)
      })
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.addEventListener('click', () => void loadThread(thread.threadId))
      append(card, title, venue, meta, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    refs.refreshButton.disabled = true
    refs.refreshButton.textContent = 'Обновляем…'
    const result = await guestGetSupportThreads(backendUrl, deps, controller.signal)
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
    if (threads.length && refs.detail.childElementCount === 0) {
      void loadThread(threads[0].threadId)
    } else if (!threads.length) {
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
    renderThreadDetail(result.data.thread, result.data.messages)
  }

  const renderThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[]) => {
    let currentMessages = messages
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
    const textarea = document.createElement('textarea')
    textarea.className = 'venue-textarea'
    textarea.placeholder = 'Напишите ответ заведению.'
    textarea.maxLength = 1000
    textarea.rows = 4
    const status = el('p', { className: 'status', text: '' })
    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
    append(actions, submitButton)
    append(card, title, meta, messagesList, textarea, status, actions)
    refs.detail.replaceChildren(card)
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

  disposables.push(on(refs.refreshButton, 'click', () => void loadThreads()))
  void loadThreads()

  return () => {
    disposed = true
    abortController?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
