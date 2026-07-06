import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  platformAssignSupportThread,
  platformChangeSupportThreadStatus,
  platformGetSupportThread,
  platformGetSupportThreads,
  platformSendSupportThreadMessage
} from '../shared/api/platformApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportMessageDto, SupportThreadDto } from '../shared/api/supportDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type PlatformSupportOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
}

type PlatformSupportFilter = 'all' | 'platform' | 'new' | 'in_work' | 'closed'

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
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString('ru-RU', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit'
      })
}

function statusLabel(thread: SupportThreadDto): string {
  if (thread.statusLabel) return thread.statusLabel
  switch (thread.status.toUpperCase()) {
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
      return thread.status
  }
}

function threadTitle(thread: SupportThreadDto): string {
  return thread.contextLabel || thread.title || `Обращение #${thread.threadId}`
}

function renderMessages(container: HTMLElement, messages: SupportMessageDto[]) {
  container.replaceChildren()
  if (!messages.length) {
    container.appendChild(el('p', { className: 'venue-empty', text: 'Сообщений пока нет.' }))
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
    container.appendChild(
      el('p', {
        className: message.authorRole === 'PLATFORM' ? 'venue-order-meta' : 'venue-order-sub',
        text: `${author}, ${formatDateTime(message.createdAt)}: ${message.text}`
      })
    )
  })
}

export function renderPlatformSupportScreen(options: PlatformSupportOptions) {
  const { root, backendUrl, isDebug } = options
  if (!root) return () => undefined
  const deps = buildApiDeps(isDebug)
  const wrapper = el('div', { className: 'venue-messages-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Обращения' })
  const hint = el('p', { className: 'venue-order-sub', text: 'Support Center платформы: все обращения и отдельно переданные платформе.' })
  const status = el('p', { className: 'status', text: '' })
  const filters = el('div', { className: 'message-filter-tabs' })
  const allButton = el('button', { className: 'button-small', text: 'Все' }) as HTMLButtonElement
  const platformButton = el('button', { className: 'button-small button-secondary', text: 'Переданные платформе' }) as HTMLButtonElement
  const newButton = el('button', { className: 'button-small button-secondary', text: 'Новые' }) as HTMLButtonElement
  const inWorkButton = el('button', { className: 'button-small button-secondary', text: 'В работе' }) as HTMLButtonElement
  const closedButton = el('button', { className: 'button-small button-secondary', text: 'Закрытые' }) as HTMLButtonElement
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  append(filters, allButton, platformButton, newButton, inWorkButton, closedButton)
  append(header, title, hint, status, filters, refreshButton)
  const layout = el('div', { className: 'venue-messages-layout' })
  const list = el('div', { className: 'venue-messages-list' })
  const detail = el('div', { className: 'venue-messages-detail' })
  append(layout, list, detail)
  append(wrapper, header, layout)
  root.replaceChildren(wrapper)

  let disposed = false
  let abortController: AbortController | null = null
  let currentFilter: PlatformSupportFilter = 'all'
  let selectedThreadId: number | null = null
  let threads: SupportThreadDto[] = []
  const disposables: Array<() => void> = []

  const setLoading = (loading: boolean) => {
    refreshButton.disabled = loading
    refreshButton.textContent = loading ? 'Обновляем…' : '🔄 Обновить'
  }

  const updateFilterButtons = () => {
    allButton.dataset.active = String(currentFilter === 'all')
    platformButton.dataset.active = String(currentFilter === 'platform')
    newButton.dataset.active = String(currentFilter === 'new')
    inWorkButton.dataset.active = String(currentFilter === 'in_work')
    closedButton.dataset.active = String(currentFilter === 'closed')
  }

  const filterThreads = (items: SupportThreadDto[]) => {
    switch (currentFilter) {
      case 'new':
        return items.filter((thread) => thread.status.toUpperCase() === 'NEW')
      case 'in_work':
        return items.filter((thread) => ['OPEN', 'IN_PROGRESS', 'WAITING_USER'].includes(thread.status.toUpperCase()))
      case 'closed':
        return items.filter((thread) => ['RESOLVED', 'CLOSED'].includes(thread.status.toUpperCase()))
      default:
        return items
    }
  }

  const renderList = () => {
    list.replaceChildren()
    if (!threads.length) {
      const empty = el('section', { className: 'card' })
      empty.appendChild(el('p', { className: 'venue-empty', text: 'Обращений нет.' }))
      list.appendChild(empty)
      return
    }
    threads.forEach((thread) => {
      const card = el('section', { className: 'card venue-message-thread-card' })
      card.dataset.selected = String(thread.threadId === selectedThreadId)
      append(
        card,
        el('h3', { text: threadTitle(thread) }),
        el('p', {
          className: 'venue-order-sub',
          text: `${thread.threadType === 'BOOKING_THREAD' ? 'Бронь' : 'Обращение'} · ${statusLabel(thread)} · ${thread.assigneeScope}`
        }),
        el('p', {
          className: 'venue-order-sub',
          text: `${thread.assigneeScope === 'PLATFORM' ? 'platform' : 'venue'} · ${thread.venueName || 'Без заведения'} · ${thread.tableLabel ?? thread.orderDisplayLabel ?? thread.category}`
        }),
        el('p', { className: 'venue-order-sub', text: `Обновлено: ${formatDateTime(thread.updatedAt)}` }),
        el('p', { className: 'message-preview', text: thread.lastMessagePreview || 'Сообщений пока нет.' })
      )
      const openButton = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      openButton.addEventListener('click', () => void loadThread(thread.threadId))
      card.appendChild(openButton)
      list.appendChild(card)
    })
  }

  const loadThreads = async () => {
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    updateFilterButtons()
    const result = await platformGetSupportThreads(
      backendUrl,
      {
        filter: 'all',
        assigneeScope: currentFilter === 'platform' ? 'PLATFORM' : null,
        threadType: 'SUPPORT_TICKET'
      },
      deps,
      controller.signal
    )
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(status, result.error, isDebug)
      return
    }
    status.textContent = ''
    threads = filterThreads(result.data.items)
    renderList()
    if (!selectedThreadId || !threads.some((thread) => thread.threadId === selectedThreadId)) {
      selectedThreadId = null
      detail.replaceChildren()
    }
  }

  const renderDetail = (thread: SupportThreadDto, messages: SupportMessageDto[]) => {
    const card = el('section', { className: 'card' })
    const messageList = el('div', { className: 'venue-support-messages' })
    renderMessages(messageList, messages)
    const meta = el('p', {
      className: 'venue-order-sub',
      text: `${thread.venueName || 'Без заведения'} · ${thread.tableLabel ?? thread.orderDisplayLabel ?? thread.category} · ${statusLabel(thread)} · ${thread.assigneeScope}`
    })
    const textarea = document.createElement('textarea')
    textarea.className = 'venue-textarea'
    textarea.placeholder = 'Ответ гостю'
    textarea.maxLength = 1000
    textarea.rows = 4
    const localStatus = el('p', { className: 'status', text: '' })
    const actions = el('div', { className: 'order-actions' })
    const sendButton = el('button', { className: 'button-small', text: 'Ответить' }) as HTMLButtonElement
    const assignVenueButton = el('button', { className: 'button-small button-secondary', text: 'Назначить заведению' }) as HTMLButtonElement
    const assignPlatformButton = el('button', { className: 'button-small button-secondary', text: 'Назначить платформе' }) as HTMLButtonElement
    const inProgressButton = el('button', { className: 'button-small button-secondary', text: 'В работу' }) as HTMLButtonElement
    const resolvedButton = el('button', { className: 'button-small button-secondary', text: 'Решено' }) as HTMLButtonElement
    const closeButton = el('button', { className: 'button-small button-secondary', text: 'Закрыть' }) as HTMLButtonElement
    append(actions, sendButton, assignVenueButton, assignPlatformButton, inProgressButton, resolvedButton, closeButton)
    assignVenueButton.disabled = !thread.venueId
    append(card, el('h3', { text: threadTitle(thread) }), meta, messageList, textarea, localStatus, actions)
    detail.replaceChildren(card)

    sendButton.addEventListener('click', async () => {
      const text = textarea.value.trim()
      if (!text) {
        localStatus.textContent = 'Введите сообщение.'
        return
      }
      sendButton.disabled = true
      const result = await platformSendSupportThreadMessage(backendUrl, thread.threadId, { message: text }, deps)
      sendButton.disabled = false
      if (!result.ok) {
        renderApiError(localStatus, result.error, isDebug)
        return
      }
      textarea.value = ''
      showToast('Ответ отправлен.')
      renderDetail(result.data.thread, [...messages, result.data.message])
      void loadThreads()
    })

    const assign = async (assigneeScope: 'VENUE' | 'PLATFORM') => {
      const result = await platformAssignSupportThread(backendUrl, thread.threadId, { assigneeScope }, deps)
      if (!result.ok) {
        renderApiError(localStatus, result.error, isDebug)
        return
      }
      showToast('Назначение обновлено.')
      renderDetail(result.data.thread, result.data.messages)
      void loadThreads()
    }
    assignVenueButton.addEventListener('click', () => void assign('VENUE'))
    assignPlatformButton.addEventListener('click', () => void assign('PLATFORM'))

    const setStatus = async (nextStatus: 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED') => {
      const result = await platformChangeSupportThreadStatus(backendUrl, thread.threadId, { status: nextStatus }, deps)
      if (!result.ok) {
        renderApiError(localStatus, result.error, isDebug)
        return
      }
      showToast('Статус обновлён.')
      renderDetail(result.data.thread, result.data.messages)
      void loadThreads()
    }
    inProgressButton.addEventListener('click', () => void setStatus('IN_PROGRESS'))
    resolvedButton.addEventListener('click', () => void setStatus('RESOLVED'))
    closeButton.addEventListener('click', () => void setStatus('CLOSED'))
  }

  const loadThread = async (threadId: number) => {
    const result = await platformGetSupportThread(backendUrl, threadId, deps)
    if (!result.ok) {
      renderApiError(status, result.error, isDebug)
      return
    }
    selectedThreadId = result.data.thread.threadId
    renderList()
    renderDetail(result.data.thread, result.data.messages)
  }

  const setFilter = (filter: PlatformSupportFilter) => {
    currentFilter = filter
    selectedThreadId = null
    detail.replaceChildren()
    void loadThreads()
  }

  disposables.push(on(allButton, 'click', () => setFilter('all')))
  disposables.push(on(platformButton, 'click', () => setFilter('platform')))
  disposables.push(on(newButton, 'click', () => setFilter('new')))
  disposables.push(on(inWorkButton, 'click', () => setFilter('in_work')))
  disposables.push(on(closedButton, 'click', () => setFilter('closed')))
  disposables.push(on(refreshButton, 'click', () => void loadThreads()))
  void loadThreads()

  return () => {
    disposed = true
    abortController?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
