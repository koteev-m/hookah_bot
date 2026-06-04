import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueAckStaffCall, venueDoneStaffCall, venueGetStaffCalls } from '../shared/api/venueApi'
import type { VenueAccessDto, VenueStaffCallDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueCallsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type CallsRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  list: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
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

function formatTime(value?: string | null) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return date.toLocaleString()
}

function buildCallsDom(root: HTMLDivElement): CallsRefs {
  const wrapper = el('div', { className: 'venue-calls' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Вызовы персонала' })
  const hint = el('p', {
    className: 'venue-order-sub',
    text: 'Новые вызовы можно принять в работу и закрыть после выполнения.'
  })
  const refreshButton = el('button', { className: 'button-secondary', text: 'Обновить' }) as HTMLButtonElement
  append(header, title, hint, refreshButton)

  const status = el('p', { className: 'status', text: '' })
  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-calls-list' })
  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  return {
    status,
    refreshButton,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    list
  }
}

function renderCallCard(
  call: VenueStaffCallDto,
  canAct: boolean,
  onAck: (call: VenueStaffCallDto) => void,
  onDone: (call: VenueStaffCallDto) => void
) {
  const card = el('div', { className: 'card venue-call-card' })
  const header = el('div', { className: 'card-header' })
  append(
    header,
    el('h3', { text: `Стол №${call.tableNumber}` }),
    el('span', { className: 'menu-item-badge', text: call.statusLabel })
  )

  const meta = el('div', { className: 'venue-order-meta' })
  append(
    meta,
    el('span', { text: `Причина: ${call.reasonLabel}` }),
    el('span', { text: `Создан: ${formatTime(call.createdAt)}` }),
    el('span', { text: `Гость: ${call.guestDisplayName || 'гость'}` })
  )
  if (call.comment?.trim()) {
    meta.appendChild(el('span', { text: `Комментарий: ${call.comment.trim()}` }))
  }

  const actions = el('div', { className: 'button-row' })
  const ackButton = el('button', { className: 'button-small', text: 'Принять' }) as HTMLButtonElement
  const doneButton = el('button', { className: 'button-small button-secondary', text: 'Закрыть' }) as HTMLButtonElement
  ackButton.disabled = !canAct || call.status !== 'NEW'
  doneButton.disabled = !canAct || call.status !== 'ACK'
  ackButton.title = ackButton.disabled ? (canAct ? '' : 'Недостаточно прав') : ''
  doneButton.title = doneButton.disabled ? (canAct ? '' : 'Недостаточно прав') : ''
  ackButton.addEventListener('click', () => onAck(call))
  doneButton.addEventListener('click', () => onDone(call))
  append(actions, ackButton, doneButton)

  append(card, header, meta, actions)
  return card
}

export function renderVenueCallsScreen(options: VenueCallsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildCallsDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let actionAbort: AbortController | null = null
  let loadSeq = 0
  const canAct = access.permissions.includes('ORDER_STATUS_UPDATE')

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: () => void loadCalls() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadCalls() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderCalls = (items: VenueStaffCallDto[]) => {
    refs.list.replaceChildren()
    if (!items.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Активных вызовов нет.' }))
      return
    }
    items.forEach((call) => {
      refs.list.appendChild(renderCallCard(call, canAct, (target) => void ackCall(target), (target) => void doneCall(target)))
    })
  }

  const loadCalls = async () => {
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetStaffCalls(backendUrl, { venueId, limit: 50 }, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    renderCalls(result.data.items)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const ackCall = async (call: VenueStaffCallDto) => {
    if (!canAct) return
    actionAbort?.abort()
    const controller = new AbortController()
    actionAbort = controller
    const result = await venueAckStaffCall(backendUrl, { venueId, staffCallId: call.id }, deps, controller.signal)
    if (disposed) return
    actionAbort = null
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast(result.data.applied ? 'Вызов принят' : 'Вызов уже обработан')
    void loadCalls()
  }

  const doneCall = async (call: VenueStaffCallDto) => {
    if (!canAct) return
    actionAbort?.abort()
    const controller = new AbortController()
    actionAbort = controller
    const result = await venueDoneStaffCall(backendUrl, { venueId, staffCallId: call.id }, deps, controller.signal)
    if (disposed) return
    actionAbort = null
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast(result.data.applied ? 'Вызов закрыт' : 'Вызов уже обработан')
    void loadCalls()
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.refreshButton, 'click', () => void loadCalls()))

  void loadCalls()

  return () => {
    disposed = true
    loadAbort?.abort()
    actionAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
