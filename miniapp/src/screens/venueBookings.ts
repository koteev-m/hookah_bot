import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCancelBooking,
  venueChangeBooking,
  venueConfirmBooking,
  venueGetBookings,
  venueGetSupportThreads,
  venueMessageBookingGuest,
  venueNoShowBooking,
  venueSeatBooking
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { SupportThreadDto } from '../shared/api/supportDtos'
import type { VenueAccessDto, VenueBookingDto } from '../shared/api/venueDtos'
import { append, el, on } from '../shared/ui/dom'
import { showToast } from '../shared/ui/toast'

type VenueBookingsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type VenueBookingsRefs = {
  status: HTMLParagraphElement
  refreshButton: HTMLButtonElement
  list: HTMLDivElement
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
      return 'перенесена'
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

function dateInputValue(booking: VenueBookingDto): string {
  if (booking.scheduledLocalDate) {
    return booking.scheduledLocalDate
  }
  const value = booking.scheduledAt
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toISOString().slice(0, 10)
}

function timeInputValue(booking: VenueBookingDto): string {
  if (booking.scheduledLocalTime) {
    return booking.scheduledLocalTime
  }
  const value = booking.scheduledAt
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toTimeString().slice(0, 5)
}

function buildChangeRequest(dateValue: string, timeValue: string) {
  if (!dateValue || !timeValue) {
    return null
  }
  return {
    scheduledLocalDate: dateValue,
    scheduledLocalTime: timeValue
  }
}

function formatDateTime(value: string): string {
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

function formatBookingTime(booking: VenueBookingDto): string {
  return booking.scheduledAtDisplay || formatDateTime(booking.scheduledAt)
}

function partySizeLabel(value?: number | null): string {
  return value == null ? 'гостей: —' : `гостей: ${value}`
}

function renderApiError(status: HTMLParagraphElement, error: ApiErrorInfo, isDebug: boolean) {
  const code = normalizeErrorCode(error)
  if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
    clearSession()
  }
  status.textContent = isDebug ? `${error.message} (${error.code})` : error.message || 'Не удалось выполнить действие.'
}

function buildDom(root: HTMLDivElement): VenueBookingsRefs {
  const wrapper = el('div', { className: 'venue-bookings-screen' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Брони' })
  const status = el('p', { className: 'status', text: '' })
  const refreshButton = el('button', { className: 'button-secondary', text: '🔄 Обновить' }) as HTMLButtonElement
  append(header, title, status, refreshButton)
  const list = el('div', { className: 'venue-bookings-list' })
  append(wrapper, header, list)
  root.replaceChildren(wrapper)
  return { status, refreshButton, list }
}

function canConfirm(booking: VenueBookingDto) {
  return booking.status === 'pending' || booking.status === 'changed'
}

function canCancel(booking: VenueBookingDto) {
  return booking.status === 'pending' || booking.status === 'confirmed' || booking.status === 'changed'
}

function canChange(booking: VenueBookingDto) {
  return canCancel(booking)
}

function canMarkArrival(booking: VenueBookingDto) {
  return booking.status === 'confirmed'
}

function renderChangeForm(
  booking: VenueBookingDto,
  onChange: (booking: VenueBookingDto, dateValue: string, timeValue: string) => void
): HTMLElement {
  const details = el('details', { className: 'venue-booking-change' })
  const summary = el('summary', { text: 'Перенести бронь' })
  const form = el('div', { className: 'order-actions' })

  const dateInput = document.createElement('input')
  dateInput.type = 'date'
  dateInput.className = 'venue-input'
  dateInput.value = dateInputValue(booking)

  const timeInput = document.createElement('input')
  timeInput.type = 'time'
  timeInput.className = 'venue-input'
  timeInput.value = timeInputValue(booking)

  const submitButton = el('button', { className: 'button-small', text: 'Перенести' }) as HTMLButtonElement
  submitButton.addEventListener('click', () => onChange(booking, dateInput.value, timeInput.value))

  append(form, dateInput, timeInput, submitButton)
  append(details, summary, form)
  return details
}

function bookingTitle(booking: VenueBookingDto): string {
  return booking.displayNumber ? `Бронь №${booking.displayNumber}` : `Бронь #${booking.bookingId}`
}

function openBookingMessageModal(
  booking: VenueBookingDto,
  options: {
    backendUrl: string
    venueId: number
    deps: ReturnType<typeof buildApiDeps>
  }
): Promise<SupportThreadDto | null> {
  return new Promise((resolve) => {
    const overlay = el('div', { className: 'venue-modal-overlay' })
    const dialog = el('section', { className: 'venue-modal card' })
    dialog.setAttribute('role', 'dialog')
    dialog.setAttribute('aria-modal', 'true')
    dialog.setAttribute('aria-labelledby', 'venue-booking-message-title')

    const title = el('h3', { id: 'venue-booking-message-title', text: 'Сообщение гостю' })
    const helper = el('p', {
      className: 'venue-order-sub',
      text: 'Сообщение придёт гостю в Telegram и появится в переписке.'
    })
    const bookingMeta = el('p', { className: 'venue-order-sub', text: bookingTitle(booking) })
    const error = el('p', { className: 'status', text: '' })
    const textarea = document.createElement('textarea')
    textarea.className = 'venue-textarea venue-booking-message-textarea'
    textarea.placeholder = 'Например: На 19:00 все столы заняты. Можем предложить 20:30?'
    textarea.maxLength = 1000
    textarea.rows = 5

    const actions = el('div', { className: 'order-actions' })
    const submitButton = el('button', { className: 'button-small', text: 'Отправить' }) as HTMLButtonElement
    const cancelButton = el('button', { className: 'button-small button-secondary', text: 'Отмена' }) as HTMLButtonElement
    let requestController: AbortController | null = null

    const setBusy = (busy: boolean) => {
      submitButton.disabled = busy
      cancelButton.disabled = busy
      submitButton.textContent = busy ? 'Отправляем…' : 'Отправить'
    }

    const close = () => {
      requestController?.abort()
      overlay.remove()
      resolve(null)
    }

    submitButton.addEventListener('click', async () => {
      const message = textarea.value.trim()
      if (!message) {
        error.textContent = 'Введите сообщение гостю.'
        textarea.focus()
        return
      }
      if (message.length > 1000) {
        error.textContent = 'Сообщение должно быть не длиннее 1000 символов.'
        textarea.focus()
        return
      }
      setBusy(true)
      requestController?.abort()
      const controller = new AbortController()
      requestController = controller
      const result = await venueMessageBookingGuest(
        options.backendUrl,
        { venueId: options.venueId, bookingId: booking.bookingId, body: { message } },
        options.deps,
        controller.signal
      )
      if (requestController !== controller) return
      setBusy(false)
      if (!result.ok) {
        error.textContent = result.error.message || 'Не удалось отправить сообщение.'
        return
      }
      textarea.value = ''
      overlay.remove()
      resolve(result.data.thread)
    })
    cancelButton.addEventListener('click', close)
    overlay.addEventListener('click', (event) => {
      if (event.target === overlay) {
        close()
      }
    })

    append(actions, submitButton, cancelButton)
    append(dialog, title, helper, bookingMeta, textarea, error, actions)
    overlay.appendChild(dialog)
    document.body.appendChild(overlay)
    window.setTimeout(() => textarea.focus(), 0)
  })
}

function renderBookings(
  list: HTMLDivElement,
  bookings: VenueBookingDto[],
  bookingThreads: Map<number, SupportThreadDto>,
  canManage: boolean,
  canMarkArrivalStatus: boolean,
  onConfirm: (booking: VenueBookingDto) => void,
  onCancel: (booking: VenueBookingDto) => void,
  onChange: (booking: VenueBookingDto, dateValue: string, timeValue: string) => void,
  onMessage: (booking: VenueBookingDto) => void,
  onOpenThread: (thread: SupportThreadDto) => void,
  onSeat: (booking: VenueBookingDto) => void,
  onNoShow: (booking: VenueBookingDto) => void
) {
  list.replaceChildren()
  if (!bookings.length) {
    const empty = el('section', { className: 'card' })
    empty.appendChild(el('p', { className: 'venue-empty', text: 'Активных броней пока нет.' }))
    list.appendChild(empty)
    return
  }
  bookings.forEach((booking) => {
    const thread = bookingThreads.get(booking.bookingId) ?? null
    const row = el('section', { className: 'card venue-booking-card' })
    row.appendChild(el('h3', { text: bookingTitle(booking) }))
    row.appendChild(
      el('p', {
        className: 'venue-order-meta',
        text: `${formatBookingTime(booking)} · ${partySizeLabel(booking.partySize)} · ${bookingStatusLabel(booking.status)}`
      })
    )
    row.appendChild(el('p', { className: 'venue-order-sub', text: `Гость: ${booking.guestDisplayName || 'Гость'}` }))
    if (booking.serviceDate) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: `Смена: ${booking.serviceDate}` }))
    }
    if (booking.arrivalDeadlineAtDisplay) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: `Держим до: ${booking.arrivalDeadlineAtDisplay}` }))
    }
    if (booking.comment) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: booking.comment }))
    }
    if (booking.status.toLowerCase() === 'changed') {
      row.appendChild(el('p', { className: 'venue-order-sub', text: `Новое время: ${formatBookingTime(booking)}` }))
    }
    const guestConfirmationAt = booking.lastGuestConfirmationAt?.trim()
    if (guestConfirmationAt) {
      row.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: `Гость подтвердил визит: ${guestConfirmationAt}`
        })
      )
    }
    if (thread) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: 'Есть переписка с гостем.' }))
    }
    const actions = el('div', { className: 'order-actions' })
    if (canManage && canConfirm(booking)) {
      const confirmButton = el('button', { className: 'button-small', text: 'Подтвердить' }) as HTMLButtonElement
      confirmButton.addEventListener('click', () => onConfirm(booking))
      actions.appendChild(confirmButton)
    }
    if (canManage && canCancel(booking)) {
      const cancelButton = el('button', { className: 'button-small button-secondary', text: 'Отменить' }) as HTMLButtonElement
      cancelButton.addEventListener('click', () => onCancel(booking))
      actions.appendChild(cancelButton)
    }
    if (canManage) {
      const messageButton = el('button', {
        className: 'button-small button-secondary',
        text: thread ? 'Открыть переписку' : 'Написать гостю'
      }) as HTMLButtonElement
      messageButton.addEventListener('click', () => {
        if (thread) {
          onOpenThread(thread)
        } else {
          onMessage(booking)
        }
      })
      actions.appendChild(messageButton)
    }
    if (canMarkArrivalStatus && canMarkArrival(booking)) {
      const seatButton = el('button', { className: 'button-small', text: 'Гость пришёл' }) as HTMLButtonElement
      seatButton.addEventListener('click', () => onSeat(booking))
      actions.appendChild(seatButton)
      const noShowButton = el('button', { className: 'button-small button-secondary', text: 'Не пришёл' }) as HTMLButtonElement
      noShowButton.addEventListener('click', () => onNoShow(booking))
      actions.appendChild(noShowButton)
    }
    if (actions.childElementCount > 0) {
      row.appendChild(actions)
    }
    if (canManage && canChange(booking)) {
      row.appendChild(renderChangeForm(booking, onChange))
    }
    list.appendChild(row)
  })
}

export function renderVenueBookingsScreen(options: VenueBookingsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)
  const disposables: Array<() => void> = []
  let disposed = false
  let isLoading = false
  let abortController: AbortController | null = null
  let currentBookings: VenueBookingDto[] = []
  let bookingThreads = new Map<number, SupportThreadDto>()
  const canManage = access.permissions.includes('BOOKING_MANAGE')
  const canMarkArrivalStatus = access.permissions.includes('BOOKING_ARRIVAL_UPDATE')

  const setLoading = (loading: boolean) => {
    isLoading = loading
    refs.refreshButton.disabled = loading
    refs.refreshButton.textContent = loading ? 'Обновляем…' : '🔄 Обновить'
  }

  const renderCurrentBookings = () => {
    renderBookings(
      refs.list,
      currentBookings,
      bookingThreads,
      canManage,
      canMarkArrivalStatus,
      (booking) => void confirmBooking(booking),
      (booking) => void cancelBooking(booking),
      (booking, dateValue, timeValue) => void changeBooking(booking, dateValue, timeValue),
      (booking) => void messageBookingGuest(booking),
      (thread) => openThread(thread),
      (booking) => void seatBooking(booking),
      (booking) => void noShowBooking(booking)
    )
  }

  const loadBookings = async () => {
    if (isLoading) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueGetBookings(backendUrl, { venueId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    if (!result.ok) {
      abortController = null
      setLoading(false)
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    currentBookings = result.data.items
    if (canManage) {
      const threadsResult = await venueGetSupportThreads(backendUrl, { venueId }, deps, controller.signal)
      if (disposed || abortController !== controller) return
      if (threadsResult.ok) {
        bookingThreads = new Map()
        threadsResult.data.items.forEach((thread) => {
          if (thread.bookingId) {
            bookingThreads.set(thread.bookingId, thread)
          }
        })
      }
    }
    abortController = null
    setLoading(false)
    refs.status.textContent = ''
    renderCurrentBookings()
  }

  const confirmBooking = async (booking: VenueBookingDto) => {
    if (isLoading || !canManage) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueConfirmBooking(backendUrl, { venueId, bookingId: booking.bookingId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showToast('Бронь подтверждена')
    await loadBookings()
  }

  const cancelBooking = async (booking: VenueBookingDto) => {
    if (isLoading || !canManage) return
    const reasonInput = window.prompt('Причина отмены для гостя. Можно оставить пустым.', '')
    if (reasonInput === null) return
    const reasonText = reasonInput.trim()
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueCancelBooking(
      backendUrl,
      {
        venueId,
        bookingId: booking.bookingId,
        body: { reasonText: reasonText || null }
      },
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
    showToast('Бронь отменена')
    await loadBookings()
  }

  const changeBooking = async (booking: VenueBookingDto, dateValue: string, timeValue: string) => {
    if (isLoading || !canManage) return
    const request = buildChangeRequest(dateValue, timeValue)
    if (!request) {
      refs.status.textContent = 'Выберите дату и время.'
      return
    }
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueChangeBooking(
      backendUrl,
      { venueId, bookingId: booking.bookingId, body: request },
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
    showToast('Бронь перенесена')
    await loadBookings()
  }

  const messageBookingGuest = async (booking: VenueBookingDto) => {
    if (isLoading || !canManage) return
    const thread = await openBookingMessageModal(booking, { backendUrl, venueId, deps })
    if (!thread || disposed) return
    bookingThreads.set(booking.bookingId, thread)
    refs.status.textContent = 'Сообщение отправлено гостю.'
    renderCurrentBookings()
    showToast('Сообщение отправлено гостю.')
  }

  const openThread = (thread: SupportThreadDto) => {
    window.location.hash = `#/messages?threadId=${thread.threadId}`
  }

  const seatBooking = async (booking: VenueBookingDto) => {
    if (isLoading || !canMarkArrivalStatus) return
    const confirmed = window.confirm('Отметить, что гость пришёл?')
    if (!confirmed) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueSeatBooking(backendUrl, { venueId, bookingId: booking.bookingId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showToast('Гость отмечен')
    await loadBookings()
  }

  const noShowBooking = async (booking: VenueBookingDto) => {
    if (isLoading || !canMarkArrivalStatus) return
    const confirmed = window.confirm('Отметить неявку гостя?')
    if (!confirmed) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueNoShowBooking(backendUrl, { venueId, bookingId: booking.bookingId }, deps, controller.signal)
    if (disposed || abortController !== controller) return
    abortController = null
    setLoading(false)
    if (!result.ok) {
      renderApiError(refs.status, result.error, isDebug)
      return
    }
    showToast('Неявка отмечена')
    await loadBookings()
  }

  disposables.push(on(refs.refreshButton, 'click', () => void loadBookings()))
  void loadBookings()

  return () => {
    disposed = true
    abortController?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
