import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueGetSupportThread, venueGetSupportThreads, venueSendSupportThreadMessage } from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportMessageDto, SupportThreadDto } from '../shared/api/supportDtos'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type VenueMessagesOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type VenueMessagesRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
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

function bookingTitle(thread: SupportThreadDto): string {
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
    const author = message.authorRole === 'GUEST' ? 'Гость' : message.authorRole === 'VENUE' ? 'Заведение' : 'Система'
    const row = el('p', {
      className: message.authorRole === 'GUEST' ? 'venue-order-sub' : 'venue-order-meta',
      text: `${author}, ${formatDateTime(message.createdAt)}: ${message.text}`
    })
    list.appendChild(row)
  })
}

function buildDom(root: HTMLDivElement): VenueMessagesRefs {
  const wrapper = el('div', { className: 'venue-messages-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Сообщения' })
  const hint = el('p', {
    className: 'venue-order-sub',
    text: 'История переписки с гостями по броням. Сообщения приходят гостям в Telegram.'
  })
  const status = el('p', { className: 'status', text: '' })
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  append(header, title, hint, status, refreshButton)
  const layout = el('div', { className: 'venue-messages-layout' })
  const list = el('div', { className: 'venue-messages-list' })
  const detail = el('div', { className: 'venue-messages-detail' })
  append(layout, list, detail)
  append(wrapper, header, layout)
  root.replaceChildren(wrapper)
  return { status, refreshButton, list, detail }
}

export function renderVenueMessagesScreen(options: VenueMessagesOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let abortController: AbortController | null = null
  let threads: SupportThreadDto[] = []
  const canReply = access.permissions.includes('BOOKING_MANAGE')

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
      const title = el('h3', { text: bookingTitle(thread) })
      const meta = el('p', {
        className: 'venue-order-sub',
        text: `${thread.status.toLowerCase()} · ${formatDateTime(thread.lastMessageAt || thread.createdAt)}`
      })
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.addEventListener('click', () => void loadThread(thread.threadId))
      append(card, title, meta, openButton)
      refs.list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueGetSupportThreads(backendUrl, { venueId }, deps, controller.signal)
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
    renderThreadDetail(result.data.thread, result.data.messages)
  }

  const renderThreadDetail = (thread: SupportThreadDto, messages: SupportMessageDto[]) => {
    const card = el('section', { className: 'card' })
    const title = el('h3', { text: bookingTitle(thread) })
    const meta = el('p', {
      className: 'venue-order-sub',
      text: thread.booking
        ? `${formatDateTime(thread.booking.scheduledAt)} · гостей: ${thread.booking.partySize ?? '—'} · ${thread.booking.status ?? ''}`
        : thread.title
    })
    let currentMessages = messages
    const messagesList = el('div', { className: 'venue-support-messages' })
    renderMessages(messagesList, currentMessages)
    const textarea = document.createElement('textarea')
    textarea.className = 'venue-textarea'
    textarea.placeholder = 'Напишите ответ гостю.'
    textarea.maxLength = 1000
    textarea.rows = 4
    const status = el('p', { className: 'status', text: '' })
    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
    append(actions, submitButton)
    append(card, title, meta, messagesList, textarea, status, actions)
    refs.detail.replaceChildren(card)

    submitButton.hidden = !canReply
    textarea.hidden = !canReply
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

  disposables.push(on(refs.refreshButton, 'click', () => void loadThreads()))
  void loadThreads()

  return () => {
    disposed = true
    abortController?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
