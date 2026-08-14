import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import {
  venueCancelConnectionApplication,
  venueGetOwnership,
  venueSubmitConnectionApplication,
  venueUpdateConnectionApplication
} from '../shared/api/venueApi'
import type {
  VenueConnectionApplicationDto,
  VenueConnectionApplicationWriteRequest,
  VenueOwnershipResponse
} from '../shared/api/venueDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError } from '../shared/ui/apiErrorPresenter'
import { showToast } from '../shared/ui/toast'

export type VenueOwnershipScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onOpenVenue: (venueId: number) => void
  onAccessRefresh: () => Promise<void>
}

type ScreenRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  venues: HTMLDivElement
  applications: HTMLDivElement
  form: HTMLFormElement
  formTitle: HTMLHeadingElement
  venueName: HTMLInputElement
  city: HTMLInputElement
  contact: HTMLInputElement
  comment: HTMLTextAreaElement
  submit: HTMLButtonElement
  reset: HTMLButtonElement
  add: HTMLButtonElement
  refresh: HTMLButtonElement
}

type OwnershipLoadOptions = {
  announcement?: string
  focusApplicationId?: number
}

function apiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function labeledControl<T extends HTMLInputElement | HTMLTextAreaElement>(
  id: string,
  labelText: string,
  control: T
) {
  const field = el('div', { className: 'ownership-field' })
  const label = el('label', { className: 'field-label', text: labelText }) as HTMLLabelElement
  control.id = id
  label.htmlFor = id
  append(field, label, control)
  return field
}

function buildDom(root: HTMLDivElement): ScreenRefs {
  const wrapper = el('div', { className: 'venue-orders ownership-workspace' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Мои заведения' })
  const lead = el('p', {
    text:
      'Здесь показаны только заведения, где у вас есть действующая роль OWNER, и ваши заявки на подключение. Новое заведение появится после решения платформы и обновления доступа.'
  })
  const headerActions = el('div', { className: 'venue-inline-actions' })
  const add = el('button', { text: 'Добавить заведение' }) as HTMLButtonElement
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить доступ' }) as HTMLButtonElement
  append(headerActions, add, refresh)
  append(header, title, lead, headerActions)

  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  status.setAttribute('aria-live', 'polite')
  status.setAttribute('aria-atomic', 'true')
  status.tabIndex = -1
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  error.setAttribute('role', 'alert')
  error.setAttribute('aria-live', 'assertive')
  error.setAttribute('aria-atomic', 'true')
  error.tabIndex = -1

  const venueCard = el('section', { className: 'card' })
  append(venueCard, el('h3', { text: 'Заведения' }))
  const venues = el('div', { className: 'venue-orders-list' }) as HTMLDivElement
  venueCard.appendChild(venues)

  const form = document.createElement('form')
  form.className = 'card ownership-application-form'
  form.hidden = true
  const formTitle = el('h3', { id: 'ownership-application-title', text: 'Новая заявка' }) as HTMLHeadingElement
  form.setAttribute('aria-labelledby', formTitle.id)
  const formGrid = el('div', { className: 'venue-form-grid' })
  const venueName = document.createElement('input')
  venueName.className = 'venue-input'
  venueName.placeholder = 'Например, «Северный дым»'
  venueName.maxLength = 200
  venueName.required = true
  venueName.autocomplete = 'organization'
  const city = document.createElement('input')
  city.className = 'venue-input'
  city.placeholder = 'Например, Казань'
  city.maxLength = 120
  city.required = true
  city.autocomplete = 'address-level2'
  const contact = document.createElement('input')
  contact.className = 'venue-input'
  contact.placeholder = 'Телефон или Telegram username'
  contact.maxLength = 200
  contact.required = true
  const comment = document.createElement('textarea')
  comment.className = 'venue-input'
  comment.placeholder = 'Дополнительная информация для платформы'
  comment.maxLength = 500
  const formActions = el('div', { className: 'venue-inline-actions' })
  const submit = el('button', { text: 'Отправить заявку' }) as HTMLButtonElement
  submit.type = 'submit'
  const reset = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  reset.type = 'button'
  append(formActions, submit, reset)
  append(
    formGrid,
    labeledControl('ownership-venue-name', 'Название заведения', venueName),
    labeledControl('ownership-city', 'Город', city),
    labeledControl('ownership-contact', 'Контакт для связи', contact),
    labeledControl('ownership-comment', 'Комментарий (необязательно)', comment)
  )
  append(form, formTitle, formGrid, formActions)

  const applicationCard = el('section', { className: 'card' })
  append(applicationCard, el('h3', { text: 'Заявки' }))
  const applications = el('div', { className: 'venue-orders-list' }) as HTMLDivElement
  applicationCard.appendChild(applications)

  append(wrapper, header, status, error, venueCard, form, applicationCard)
  root.replaceChildren(wrapper)
  return {
    status,
    error,
    venues,
    applications,
    form,
    formTitle,
    venueName,
    city,
    contact,
    comment,
    submit,
    reset,
    add,
    refresh
  }
}

function applicationStatusCopy(application: VenueConnectionApplicationDto): string {
  switch (application.status) {
    case 'PENDING':
      return 'На рассмотрении'
    case 'APPROVED':
      return application.linkedVenueId
        ? 'Одобрено · заведение добавлено'
        : 'Заявка одобрена. Заведение ещё подготавливается и скоро появится в списке.'
    case 'REJECTED':
      return 'Отклонено'
    case 'CANCELLED':
      return 'Отменено'
  }
}

function venueStatusCopy(status?: string | null) {
  const labels: Record<string, string> = {
    DRAFT: 'Черновик',
    PUBLISHED: 'Опубликовано',
    HIDDEN: 'Скрыто',
    PAUSED: 'На паузе',
    SUSPENDED: 'Приостановлено',
    ARCHIVED: 'Архив'
  }
  return status ? labels[status.toUpperCase()] ?? status : 'Статус не указан'
}

export function renderVenueOwnershipScreen(options: VenueOwnershipScreenOptions) {
  const { root, backendUrl, isDebug, onOpenVenue, onAccessRefresh } = options
  if (!root) return () => undefined
  const refs = buildDom(root)
  const deps = apiDeps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let loadSeq = 0
  let editingId: number | null = null
  let busy = false
  let mutationController: AbortController | null = null

  const setBusy = (nextBusy: boolean) => {
    busy = nextBusy
    refs.submit.disabled = nextBusy
    refs.reset.disabled = nextBusy
    refs.add.disabled = nextBusy
    refs.refresh.disabled = nextBusy
    refs.venueName.disabled = nextBusy
    refs.city.disabled = nextBusy
    refs.contact.disabled = nextBusy
    refs.comment.disabled = nextBusy
  }

  const showError = (error: ApiErrorInfo, retry?: () => void) => {
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    const actions = el('div', { className: 'error-actions' })
    presentation.actions.forEach((action) => {
      const button = el('button', {
        className: action.kind === 'secondary' ? 'button-secondary' : undefined,
        text: action.label
      }) as HTMLButtonElement
      button.addEventListener('click', action.label === 'Повторить' && retry ? retry : action.onClick)
      actions.appendChild(button)
    })
    refs.error.hidden = false
    refs.error.dataset.severity = presentation.severity
    refs.error.replaceChildren(
      el('h3', { text: presentation.title }),
      el('p', { text: presentation.message }),
      actions
    )
    refs.error.focus({ preventScroll: true })
  }

  const hideError = () => {
    refs.error.hidden = true
    refs.error.replaceChildren()
  }

  const closeForm = (focusAdd = false) => {
    editingId = null
    refs.form.reset()
    refs.form.hidden = true
    refs.formTitle.textContent = 'Новая заявка'
    refs.submit.textContent = 'Отправить заявку'
    if (focusAdd) refs.add.focus({ preventScroll: true })
  }

  const openForm = (application?: VenueConnectionApplicationDto) => {
    if (busy) return
    editingId = application?.id ?? null
    refs.formTitle.textContent = application ? `Изменить заявку #${application.id}` : 'Новая заявка'
    refs.submit.textContent = application ? 'Сохранить' : 'Отправить заявку'
    refs.venueName.value = application?.venueName ?? ''
    refs.city.value = application?.city ?? ''
    refs.contact.value = application?.contact ?? ''
    refs.comment.value = application?.comment ?? ''
    refs.form.hidden = false
    refs.venueName.focus()
  }

  const payload = (): VenueConnectionApplicationWriteRequest => ({
    venueName: refs.venueName.value.trim(),
    city: refs.city.value.trim(),
    contact: refs.contact.value.trim(),
    comment: refs.comment.value.trim() || null
  })

  const renderSnapshot = (data: VenueOwnershipResponse) => {
    refs.venues.replaceChildren()
    if (!data.venues.length) {
      refs.venues.appendChild(el('p', { className: 'venue-empty', text: 'Действующих заведений пока нет.' }))
    } else {
      data.venues.forEach((venue) => {
        const row = el('div', { className: 'venue-order-row' })
        const meta = el('div', { className: 'venue-order-meta' })
        append(
          meta,
          el('strong', { text: venue.venueName?.trim() || `Заведение #${venue.venueId}` }),
          el('p', {
            className: 'venue-order-sub',
            text: [venue.venueCity?.trim(), venueStatusCopy(venue.venueStatus)].filter(Boolean).join(' · ')
          })
        )
        const open = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
        open.addEventListener('click', () => onOpenVenue(venue.venueId))
        append(row, meta, open)
        refs.venues.appendChild(row)
      })
    }

    refs.applications.replaceChildren()
    if (!data.applications.length) {
      refs.applications.appendChild(el('p', { className: 'venue-empty', text: 'Заявок пока нет.' }))
      return
    }
    data.applications.forEach((application) => {
      const row = el('div', { className: 'venue-order-row' })
      row.dataset.applicationId = String(application.id)
      row.dataset.applicationStatus = application.status
      row.tabIndex = -1
      const meta = el('div', { className: 'venue-order-meta' })
      append(
        meta,
        el('strong', { text: application.venueName }),
        el('p', { className: 'venue-order-sub', text: `#${application.id} · ${application.city}` }),
        el('p', { className: 'ownership-status', text: applicationStatusCopy(application) }),
        el('p', { className: 'venue-order-sub', text: `Контакт: ${application.contact}` })
      )
      if (application.comment) {
        meta.appendChild(el('p', { className: 'venue-order-sub', text: application.comment }))
      }
      const actions = el('div', { className: 'venue-order-actions' })
      if (application.status === 'PENDING') {
        const edit = el('button', { className: 'button-small button-secondary', text: 'Изменить' }) as HTMLButtonElement
        const cancel = el('button', { className: 'button-small button-danger', text: 'Отменить' }) as HTMLButtonElement
        edit.addEventListener('click', () => openForm(application))
        cancel.addEventListener('click', () => void cancelApplication(application))
        append(actions, edit, cancel)
      }
      if (application.linkedVenueId) {
        const reload = el('button', { className: 'button-small', text: 'Обновить список' }) as HTMLButtonElement
        reload.addEventListener('click', () => void refreshAccessAndData())
        actions.appendChild(reload)
      }
      append(row, meta, actions)
      refs.applications.appendChild(row)
    })
  }

  const load = async (options: OwnershipLoadOptions = {}) => {
    controller?.abort()
    const active = new AbortController()
    controller = active
    const seq = ++loadSeq
    hideError()
    refs.status.textContent = 'Загрузка...'
    const result = await venueGetOwnership(backendUrl, deps, active.signal)
    if (disposed || seq !== loadSeq) return
    controller = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      refs.status.textContent = ''
      showError(result.error, () => void load())
      return
    }
    refs.status.textContent = options.announcement ?? ''
    renderSnapshot(result.data)
    if (options.focusApplicationId != null) {
      const row = refs.applications.querySelector<HTMLElement>(
        `[data-application-id="${options.focusApplicationId}"]`
      )
      row?.focus({ preventScroll: true })
    }
  }

  const refreshAccessAndData = async () => {
    if (busy) return
    setBusy(true)
    try {
      await onAccessRefresh()
      if (!disposed) await load()
    } finally {
      if (!disposed) setBusy(false)
    }
  }

  const cancelApplication = async (application: VenueConnectionApplicationDto) => {
    if (busy) return
    const confirmed = window.confirm(
      `Отменить заявку «${application.venueName}»? После отмены платформа больше не будет её обрабатывать.`
    )
    if (!confirmed) return
    setBusy(true)
    const active = new AbortController()
    mutationController = active
    refs.status.textContent = `Отменяем заявку #${application.id}...`
    const result = await venueCancelConnectionApplication(backendUrl, application.id, deps, active.signal)
    if (disposed) return
    mutationController = null
    setBusy(false)
    if (!result.ok) {
      refs.status.textContent = ''
      if (result.error.code === REQUEST_ABORTED_CODE) return
      showError(result.error, () => void cancelApplication(application))
      return
    }
    showToast('Заявка отменена.')
    await load({
      announcement: `Заявка #${result.data.application.id} отменена.`,
      focusApplicationId: result.data.application.id
    })
  }

  const submitHandler = async (event: Event) => {
    event.preventDefault()
    if (busy) return
    hideError()
    if (!refs.form.reportValidity()) return
    setBusy(true)
    const wasEditing = editingId != null
    const active = new AbortController()
    mutationController = active
    refs.status.textContent = wasEditing ? 'Сохраняем изменения...' : 'Отправляем заявку...'
    const result = wasEditing
      ? await venueUpdateConnectionApplication(backendUrl, editingId!, payload(), deps, active.signal)
      : await venueSubmitConnectionApplication(backendUrl, payload(), deps, active.signal)
    if (disposed) return
    mutationController = null
    setBusy(false)
    if (!result.ok) {
      refs.status.textContent = ''
      if (result.error.code === REQUEST_ABORTED_CODE) return
      showError(result.error, () => refs.form.requestSubmit())
      return
    }
    const announcement = wasEditing
      ? `Заявка #${result.data.application.id} обновлена.`
      : result.data.created === false
        ? `Заявка #${result.data.application.id} уже была отправлена. Показан актуальный статус.`
        : `Заявка #${result.data.application.id} отправлена.`
    closeForm()
    showToast(announcement)
    await load({ announcement, focusApplicationId: result.data.application.id })
  }

  const disposables = [
    on(refs.add, 'click', () => openForm()),
    on(refs.reset, 'click', () => closeForm(true)),
    on(refs.refresh, 'click', () => void refreshAccessAndData()),
    on(refs.form, 'submit', submitHandler)
  ]

  void load()

  return () => {
    disposed = true
    controller?.abort()
    controller = null
    mutationController?.abort()
    mutationController = null
    disposables.forEach((dispose) => dispose())
  }
}
