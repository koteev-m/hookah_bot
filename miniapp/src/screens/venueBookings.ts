import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCancelBooking,
  venueChangeBooking,
  venueConfirmBooking,
  venueGetBookings,
  venueNoShowBooking,
  venueSeatBooking
} from '../shared/api/venueApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
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

function dateInputValue(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toISOString().slice(0, 10)
}

function timeInputValue(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  return date.toTimeString().slice(0, 5)
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
  return booking.status === 'pending' || booking.status === 'confirmed' || booking.status === 'changed'
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
  dateInput.value = dateInputValue(booking.scheduledAt)

  const timeInput = document.createElement('input')
  timeInput.type = 'time'
  timeInput.className = 'venue-input'
  timeInput.value = timeInputValue(booking.scheduledAt)

  const submitButton = el('button', { className: 'button-small', text: 'Перенести' }) as HTMLButtonElement
  submitButton.addEventListener('click', () => onChange(booking, dateInput.value, timeInput.value))

  append(form, dateInput, timeInput, submitButton)
  append(details, summary, form)
  return details
}

function renderBookings(
  list: HTMLDivElement,
  bookings: VenueBookingDto[],
  canManage: boolean,
  canMarkArrivalStatus: boolean,
  onConfirm: (booking: VenueBookingDto) => void,
  onCancel: (booking: VenueBookingDto) => void,
  onChange: (booking: VenueBookingDto, dateValue: string, timeValue: string) => void,
  onSeat: (booking: VenueBookingDto) => void,
  onNoShow: (booking: VenueBookingDto) => void
) {
  list.replaceChildren()
  if (!bookings.length) {
    const empty = el('section', { className: 'card' })
    empty.appendChild(el('p', { className: 'venue-empty', text: 'Активных броней нет.' }))
    list.appendChild(empty)
    return
  }
  bookings.forEach((booking) => {
    const row = el('section', { className: 'card venue-booking-card' })
    const title = booking.displayNumber ? `Бронь №${booking.displayNumber}` : `Бронь #${booking.bookingId}`
    row.appendChild(el('h3', { text: title }))
    row.appendChild(
      el('p', {
        className: 'venue-order-meta',
        text: `${formatBookingTime(booking.scheduledAt)} · ${booking.partySize ?? '—'} гостей · ${bookingStatusLabel(booking.status)}`
      })
    )
    row.appendChild(el('p', { className: 'venue-order-sub', text: `Гость: ${booking.guestDisplayName || 'Гость'}` }))
    if (booking.comment) {
      row.appendChild(el('p', { className: 'venue-order-sub', text: booking.comment }))
    }
    if (booking.status.toLowerCase() === 'changed') {
      row.appendChild(el('p', { className: 'venue-order-sub', text: `Новое время: ${formatBookingTime(booking.scheduledAt)}` }))
    }
    if (booking.lastGuestConfirmationAt) {
      row.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: `Гость подтвердил: ${formatBookingTime(booking.lastGuestConfirmationAt)}`
        })
      )
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
  const canManage = access.permissions.includes('BOOKING_MANAGE')
  const canMarkArrivalStatus = access.permissions.includes('BOOKING_ARRIVAL_UPDATE')

  const setLoading = (loading: boolean) => {
    isLoading = loading
    refs.refreshButton.disabled = loading
    refs.refreshButton.textContent = loading ? 'Обновляем…' : '🔄 Обновить'
  }

  const loadBookings = async () => {
    if (isLoading) return
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueGetBookings(backendUrl, { venueId }, deps, controller.signal)
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
      canManage,
      canMarkArrivalStatus,
      (booking) => void confirmBooking(booking),
      (booking) => void cancelBooking(booking),
      (booking, dateValue, timeValue) => void changeBooking(booking, dateValue, timeValue),
      (booking) => void seatBooking(booking),
      (booking) => void noShowBooking(booking)
    )
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
    const scheduledAt = buildScheduledAt(dateValue, timeValue)
    if (!scheduledAt) {
      refs.status.textContent = 'Выберите дату и время.'
      return
    }
    abortController?.abort()
    const controller = new AbortController()
    abortController = controller
    setLoading(true)
    const result = await venueChangeBooking(
      backendUrl,
      { venueId, bookingId: booking.bookingId, body: { scheduledAt } },
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
