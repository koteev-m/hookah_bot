import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestCancelBooking, guestConfirmBooking, guestCreateBooking, guestGetBookings } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { GuestBookingResponse } from '../shared/api/guestDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

const MAX_COMMENT_LENGTH = 500
const ACTIVE_BOOKING_PAST_GRACE_MS = 2 * 60 * 60 * 1000
const ACTIVE_BOOKING_STATUSES = new Set(['pending', 'confirmed', 'changed'])

type GuestBookingsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number | null
  onBack: () => void
}

type GuestBookingRefs = {
  formCard: HTMLElement
  successCard: HTMLElement
  successMeta: HTMLParagraphElement
  status: HTMLParagraphElement
  dateInput: HTMLInputElement
  timeInput: HTMLInputElement
  partySizeInput: HTMLInputElement
  commentInput: HTMLTextAreaElement
  commentCounter: HTMLParagraphElement
  submitButton: HTMLButtonElement
  refreshButton: HTMLButtonElement
  successMyBookingsButton: HTMLButtonElement
  successCreateAnotherButton: HTMLButtonElement
  successBackButton: HTMLButtonElement
  list: HTMLDivElement
  backButton: HTMLButtonElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function bookingStatusLabel(status: string): string {
  switch (status.toLowerCase()) {
    case 'pending':
      return 'ожидает подтверждения'
    case 'confirmed':
      return 'подтверждена'
    case 'changed':
      return 'предложено другое время'
    case 'canceled':
      return 'отменена'
    case 'expired':
      return 'истекла'
    case 'seated':
      return 'гость пришёл'
    case 'no_show':
      return 'не пришёл'
    default:
      return status
  }
}

function formatBookingTime(value: string): string {
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

function isActiveBooking(booking: GuestBookingResponse): boolean {
  if (!ACTIVE_BOOKING_STATUSES.has(booking.status.toLowerCase())) {
    return false
  }
  const scheduledAtMs = new Date(booking.scheduledAt).getTime()
  if (Number.isNaN(scheduledAtMs)) {
    return true
  }
  return scheduledAtMs + ACTIVE_BOOKING_PAST_GRACE_MS >= Date.now()
}

function defaultDateValue(): string {
  const date = new Date()
  date.setDate(date.getDate() + 1)
  return date.toISOString().slice(0, 10)
}

function buildScheduledAt(dateValue: string, timeValue: string): string | null {
  if (!dateValue || !timeValue) {
    return null
  }
  const date = new Date(`${dateValue}T${timeValue}:00`)
  if (Number.isNaN(date.getTime())) {
    return null
  }
  return date.toISOString()
}

function isChangedBooking(booking: GuestBookingResponse): boolean {
  return booking.status.toLowerCase() === 'changed'
}

function buildDom(root: HTMLDivElement, venueId: number | null): GuestBookingRefs {
  const wrapper = el('div', { className: 'guest-bookings-screen' })
  const card = el('section', { className: 'card' })
  const title = el('h2', { text: 'Бронирование' })
  const status = el('p', { className: 'status', text: venueId ? '' : 'Выберите заведение для бронирования.' })

  const dateLabel = el('p', { className: 'field-label', text: 'Дата' })
  const dateInput = document.createElement('input')
  dateInput.type = 'date'
  dateInput.className = 'venue-input'
  dateInput.value = defaultDateValue()

  const timeLabel = el('p', { className: 'field-label', text: 'Время' })
  const timeInput = document.createElement('input')
  timeInput.type = 'time'
  timeInput.className = 'venue-input'
  timeInput.value = '19:00'

  const partySizeLabel = el('p', { className: 'field-label', text: 'Количество гостей' })
  const partySizeInput = document.createElement('input')
  partySizeInput.type = 'number'
  partySizeInput.className = 'venue-input'
  partySizeInput.min = '1'
  partySizeInput.max = '30'
  partySizeInput.value = '2'

  const commentLabel = el('p', { className: 'field-label', text: 'Комментарий (необязательно)' })
  const commentInput = document.createElement('textarea')
  commentInput.className = 'staff-comment'
  commentInput.maxLength = MAX_COMMENT_LENGTH
  commentInput.rows = 3
  commentInput.placeholder = 'Например: хотим у окна'
  const commentCounter = el('p', { className: 'field-counter', text: `0/${MAX_COMMENT_LENGTH}` })

  const actions = el('div', { className: 'button-row order-actions' })
  const submitButton = el('button', { text: 'Отправить заявку' }) as HTMLButtonElement
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  const backButton = el('button', { className: 'button-secondary', text: 'Вернуться' }) as HTMLButtonElement
  append(actions, submitButton, refreshButton, backButton)

  append(
    card,
    title,
    status,
    dateLabel,
    dateInput,
    timeLabel,
    timeInput,
    partySizeLabel,
    partySizeInput,
    commentLabel,
    commentInput,
    commentCounter,
    actions
  )

  const successCard = el('section', { className: 'card' })
  successCard.hidden = true
  successCard.appendChild(el('h2', { text: 'Заявка на бронь отправлена' }))
  successCard.appendChild(el('p', { text: 'Статус: ожидает подтверждения' }))
  const successMeta = el('p', { className: 'venue-order-meta' })
  const successActions = el('div', { className: 'button-row order-actions' })
  const successMyBookingsButton = el('button', { text: 'Мои брони' }) as HTMLButtonElement
  const successCreateAnotherButton = el('button', { className: 'button-secondary', text: 'Создать ещё' }) as HTMLButtonElement
  const successBackButton = el('button', { className: 'button-secondary', text: 'Назад к заведению' }) as HTMLButtonElement
  append(successActions, successMyBookingsButton, successCreateAnotherButton, successBackButton)
  append(successCard, successMeta, successActions)

  const list = el('div', { className: 'guest-bookings-list' })
  append(wrapper, card, successCard, list)
  root.replaceChildren(wrapper)

  return {
    formCard: card,
    successCard,
    successMeta,
    status,
    dateInput,
    timeInput,
    partySizeInput,
    commentInput,
    commentCounter,
    submitButton,
    refreshButton,
    successMyBookingsButton,
    successCreateAnotherButton,
    successBackButton,
    list,
    backButton
  }
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug ? `${error.message} (${error.code})` : error.message || 'Не удалось выполнить действие.'
}

function renderBookings(
  list: HTMLDivElement,
  bookings: GuestBookingResponse[],
  onCancel: (booking: GuestBookingResponse) => void,
  onConfirm: (booking: GuestBookingResponse) => void,
  onRefresh: () => void
) {
  list.replaceChildren()
  const section = el('section', { className: 'card' })
  const header = el('div', { className: 'venue-order-header' })
  header.appendChild(el('h3', { text: 'Мои бронирования' }))
  const refreshButton = el('button', { className: 'button-small button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  refreshButton.addEventListener('click', onRefresh)
  header.appendChild(refreshButton)
  section.appendChild(header)

  const activeBookings = bookings.filter(isActiveBooking)
  if (!activeBookings.length) {
    section.appendChild(el('p', { className: 'venue-empty', text: 'Активных броней нет.' }))
    section.appendChild(el('p', { className: 'venue-order-sub', text: 'История бронирований будет доступна в профиле.' }))
    list.appendChild(section)
    return
  }
  activeBookings.forEach((booking) => {
    const row = el('div', { className: 'venue-order-row' })
    const info = el('div')
    info.appendChild(el('strong', { text: `Бронь №${booking.bookingId}` }))
    info.appendChild(
      el('p', {
        className: 'venue-order-meta',
        text: `${formatBookingTime(booking.scheduledAt)} · ${booking.partySize ?? '—'} гостей · ${bookingStatusLabel(booking.status)}`
      })
    )
    if (booking.comment) {
      info.appendChild(el('p', { className: 'venue-order-sub', text: booking.comment }))
    }
    if (isChangedBooking(booking)) {
      info.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: `Заведение предложило новое время: ${formatBookingTime(booking.scheduledAt)}`
        })
      )
      if (booking.lastGuestConfirmationAt) {
        info.appendChild(
          el('p', {
            className: 'venue-order-sub',
            text: `Вы подтвердили новое время: ${formatBookingTime(booking.lastGuestConfirmationAt)}`
          })
        )
      }
    }
    append(row, info)
    if (isChangedBooking(booking) && !booking.lastGuestConfirmationAt) {
      const confirmButton = el('button', { className: 'button-small', text: 'Принять новое время' }) as HTMLButtonElement
      confirmButton.addEventListener('click', () => onConfirm(booking))
      row.appendChild(confirmButton)
    }
    const cancelButton = el('button', { className: 'button-small button-secondary', text: 'Отменить' }) as HTMLButtonElement
    cancelButton.addEventListener('click', () => onCancel(booking))
    row.appendChild(cancelButton)
    section.appendChild(row)
  })
  list.appendChild(section)
}

export function renderGuestBookingsScreen(options: GuestBookingsOptions) {
  const { root, backendUrl, isDebug, venueId, onBack } = options
  if (!root) return () => undefined

  const refs = buildDom(root, venueId)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let isLoading = false
  let abortController: AbortController | null = null

  const setLoading = (loading: boolean) => {
    isLoading = loading
    refs.submitButton.disabled = loading || !venueId
    refs.refreshButton.disabled = loading || !venueId
    refs.submitButton.textContent = loading ? 'Отправляем…' : 'Отправить заявку'
  }

  const resetForm = () => {
    refs.dateInput.value = defaultDateValue()
    refs.timeInput.value = '19:00'
    refs.partySizeInput.value = '2'
    refs.commentInput.value = ''
    refs.commentCounter.textContent = `0/${MAX_COMMENT_LENGTH}`
    refs.status.textContent = venueId ? '' : 'Выберите заведение для бронирования.'
  }

  const showForm = () => {
    refs.successCard.hidden = true
    refs.formCard.hidden = false
  }

  const showSuccess = (booking: GuestBookingResponse) => {
    refs.formCard.hidden = true
    refs.successCard.hidden = false
    refs.successMeta.textContent = `${formatBookingTime(booking.scheduledAt)} · ${booking.partySize ?? '—'} гостей`
  }

  const loadBookings = async () => {
    if (!venueId || isLoading) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await guestGetBookings(backendUrl, venueId, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    refs.status.textContent = ''
    renderBookings(
      refs.list,
      result.data.items,
      (booking) => void cancelBooking(booking),
      (booking) => void confirmBooking(booking),
      () => void loadBookings()
    )
  }

  const submitBooking = async () => {
    if (!venueId || isLoading) return
    const scheduledAt = buildScheduledAt(refs.dateInput.value, refs.timeInput.value)
    if (!scheduledAt) {
      refs.status.textContent = 'Выберите дату и время.'
      return
    }
    const partySize = Number(refs.partySizeInput.value)
    if (!Number.isInteger(partySize) || partySize < 1 || partySize > 30) {
      refs.status.textContent = 'Количество гостей должно быть от 1 до 30.'
      return
    }
    const comment = refs.commentInput.value.trim()
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await guestCreateBooking(
      backendUrl,
      { venueId, scheduledAt, partySize, comment: comment || null },
      deps,
      controller.signal
    )
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showSuccess(result.data)
    resetForm()
    showToast('Заявка на бронь отправлена')
    await loadBookings()
  }

  const cancelBooking = async (booking: GuestBookingResponse) => {
    if (!venueId || isLoading) return
    const confirmed = window.confirm('Отменить бронь?')
    if (!confirmed) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await guestCancelBooking(backendUrl, venueId, { bookingId: booking.bookingId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showToast('Бронь отменена')
    await loadBookings()
  }

  const confirmBooking = async (booking: GuestBookingResponse) => {
    if (!venueId || isLoading) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await guestConfirmBooking(backendUrl, venueId, { bookingId: booking.bookingId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showToast('Новое время подтверждено')
    await loadBookings()
  }

  disposables.push(
    on(refs.submitButton, 'click', () => void submitBooking()),
    on(refs.refreshButton, 'click', () => void loadBookings()),
    on(refs.backButton, 'click', onBack),
    on(refs.successMyBookingsButton, 'click', () => {
      void loadBookings()
      refs.list.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }),
    on(refs.successCreateAnotherButton, 'click', showForm),
    on(refs.successBackButton, 'click', onBack),
    on(refs.commentInput, 'input', () => {
      if (refs.commentInput.value.length > MAX_COMMENT_LENGTH) {
        refs.commentInput.value = refs.commentInput.value.slice(0, MAX_COMMENT_LENGTH)
      }
      refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
    })
  )

  setLoading(false)
  void loadBookings()

  return () => {
    disposed = true
    abortController?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
