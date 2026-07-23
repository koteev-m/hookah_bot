import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueArchivePromotion,
  venueCreatePromotion,
  venueGetPromotions,
  venueSetPromotionStatus,
  venueUpdatePromotion
} from '../shared/api/venueApi'
import type {
  VenueAccessDto,
  VenuePromotionDto,
  VenuePromotionMutationRequest,
  VenuePromotionStatus
} from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const TITLE_MAX_LENGTH = 80
const DESCRIPTION_MAX_LENGTH = 1000
const TERMS_MAX_LENGTH = 1000

type VenuePromotionsOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type PromotionRefs = {
  status: HTMLParagraphElement
  createButton: HTMLButtonElement
  formCard: HTMLElement
  formTitle: HTMLHeadingElement
  titleInput: HTMLInputElement
  descriptionInput: HTMLTextAreaElement
  termsInput: HTMLTextAreaElement
  startsAtInput: HTMLInputElement
  endsAtInput: HTMLInputElement
  formError: HTMLParagraphElement
  saveButton: HTMLButtonElement
  cancelButton: HTMLButtonElement
  list: HTMLDivElement
  empty: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

type PromotionGroup = {
  key: string
  title: string
  items: VenuePromotionDto[]
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

function buildPromotionsDom(root: HTMLDivElement): PromotionRefs {
  const wrapper = el('div', { className: 'venue-promotions' })
  const header = el('section', { className: 'card venue-promotions-header' })
  const title = el('h2', { text: 'Акции' })
  const subtitle = el('p', {
    className: 'venue-dashboard-subtitle',
    text: 'Создавайте информационные акции и управляйте периодом их показа гостям.'
  })
  const notice = el('p', {
    className: 'venue-promotion-notice',
    text: 'Акция носит информационный характер. Скидки и промокоды автоматически к заказу не применяются.'
  })
  const createButton = el('button', { text: 'Создать акцию' }) as HTMLButtonElement
  append(header, title, subtitle, notice, createButton)

  const status = el('p', { className: 'status', text: '' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const formCard = el('section', { className: 'card venue-promotion-form' })
  formCard.hidden = true
  const formTitle = el('h3', { text: 'Новая акция' })
  const titleInput = document.createElement('input')
  titleInput.type = 'text'
  titleInput.maxLength = TITLE_MAX_LENGTH
  titleInput.placeholder = 'Название акции'
  const descriptionInput = document.createElement('textarea')
  descriptionInput.rows = 4
  descriptionInput.maxLength = DESCRIPTION_MAX_LENGTH
  descriptionInput.placeholder = 'Описание'
  const termsInput = document.createElement('textarea')
  termsInput.rows = 3
  termsInput.maxLength = TERMS_MAX_LENGTH
  termsInput.placeholder = 'Условия (необязательно)'
  const startsAtInput = document.createElement('input')
  startsAtInput.type = 'datetime-local'
  const endsAtInput = document.createElement('input')
  endsAtInput.type = 'datetime-local'
  const formError = el('p', { className: 'field-error', text: '' }) as HTMLParagraphElement
  formError.hidden = true
  const saveButton = el('button', { text: 'Сохранить черновик' }) as HTMLButtonElement
  const cancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const actions = el('div', { className: 'button-row' })
  append(actions, saveButton, cancelButton)
  append(
    formCard,
    formTitle,
    buildField('Название акции', titleInput),
    buildField('Описание', descriptionInput),
    buildField('Условия', termsInput, 'Необязательно'),
    buildField('Начало', startsAtInput),
    buildField('Окончание', endsAtInput),
    formError,
    actions
  )

  const list = el('div', { className: 'venue-promotion-groups' }) as HTMLDivElement
  const empty = el('p', { className: 'venue-empty', text: 'Акций пока нет.' }) as HTMLParagraphElement
  append(wrapper, header, status, error, formCard, empty, list)
  root.replaceChildren(wrapper)

  return {
    status,
    createButton,
    formCard,
    formTitle,
    titleInput,
    descriptionInput,
    termsInput,
    startsAtInput,
    endsAtInput,
    formError,
    saveButton,
    cancelButton,
    list,
    empty,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
}

function buildField(label: string, control: HTMLElement, helper?: string) {
  const field = el('label', { className: 'venue-promotion-field' })
  field.appendChild(el('span', { className: 'field-label', text: label }))
  field.appendChild(control)
  if (helper) {
    field.appendChild(el('small', { text: helper }))
  }
  return field
}

function formatDateTime(value: string | null | undefined, timezone: string): string {
  if (!value) return 'не указано'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  try {
    return date.toLocaleString('ru-RU', {
      timeZone: timezone,
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    })
  } catch {
    return date.toLocaleString('ru-RU')
  }
}

function toVenueLocalInput(value: string | null | undefined, timezone: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  try {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23'
    }).formatToParts(date)
    const byType = Object.fromEntries(parts.map((part) => [part.type, part.value]))
    return `${byType.year}-${byType.month}-${byType.day}T${byType.hour}:${byType.minute}`
  } catch {
    return value.slice(0, 16)
  }
}

function statusLabel(status: VenuePromotionStatus): string {
  switch (status) {
    case 'DRAFT':
      return 'Черновик'
    case 'ACTIVE':
      return 'Активна'
    case 'PAUSED':
      return 'Приостановлена'
    case 'ARCHIVED':
      return 'Архив'
  }
}

function groupPromotions(items: VenuePromotionDto[]): PromotionGroup[] {
  const now = Date.now()
  const active: VenuePromotionDto[] = []
  const scheduled: VenuePromotionDto[] = []
  const expired: VenuePromotionDto[] = []
  const drafts: VenuePromotionDto[] = []
  const paused: VenuePromotionDto[] = []
  const archived: VenuePromotionDto[] = []

  items.forEach((item) => {
    if (item.status === 'DRAFT') {
      drafts.push(item)
      return
    }
    if (item.status === 'PAUSED') {
      paused.push(item)
      return
    }
    if (item.status === 'ARCHIVED') {
      archived.push(item)
      return
    }
    const startsAt = item.startsAt ? new Date(item.startsAt).getTime() : Number.NEGATIVE_INFINITY
    const endsAt = item.endsAt ? new Date(item.endsAt).getTime() : Number.POSITIVE_INFINITY
    if (Number.isFinite(startsAt) && startsAt > now) {
      scheduled.push(item)
    } else if (Number.isFinite(endsAt) && endsAt < now) {
      expired.push(item)
    } else {
      active.push(item)
    }
  })

  return [
    { key: 'active', title: 'Действуют сейчас', items: active },
    { key: 'scheduled', title: 'Запланированы', items: scheduled },
    { key: 'drafts', title: 'Черновики', items: drafts },
    { key: 'paused', title: 'Приостановлены', items: paused },
    { key: 'expired', title: 'Период завершён', items: expired },
    { key: 'archived', title: 'Архив', items: archived }
  ].filter((group) => group.items.length > 0)
}

export function renderVenuePromotionsScreen(options: VenuePromotionsOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined

  const refs = buildPromotionsDom(root)
  const deps = buildApiDeps(isDebug)
  const canManage = access.role === 'OWNER' || access.role === 'MANAGER'
  let disposed = false
  let loadAbort: AbortController | null = null
  let mutationAbort: AbortController | null = null
  let inFlight = false
  let mutationPending = false
  let items: VenuePromotionDto[] = []
  let timezone = 'Europe/Moscow'
  let editingId: number | null = null
  const cardDisposables: Array<() => void> = []

  const hideError = () => {
    refs.error.hidden = true
    refs.errorActions.replaceChildren()
    refs.errorDetails.replaceChildren()
  }

  const showError = (error: ApiErrorInfo, retry: () => void) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: retry } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const resetForm = () => {
    editingId = null
    refs.formTitle.textContent = 'Новая акция'
    refs.saveButton.textContent = 'Сохранить черновик'
    refs.titleInput.value = ''
    refs.descriptionInput.value = ''
    refs.termsInput.value = ''
    refs.startsAtInput.value = ''
    refs.endsAtInput.value = ''
    refs.formError.textContent = ''
    refs.formError.hidden = true
    refs.formCard.hidden = true
  }

  const openCreateForm = () => {
    resetForm()
    refs.formCard.hidden = false
    refs.titleInput.focus()
  }

  const openEditForm = (promotion: VenuePromotionDto) => {
    editingId = promotion.id
    refs.formTitle.textContent = 'Редактировать акцию'
    refs.saveButton.textContent = 'Сохранить изменения'
    refs.titleInput.value = promotion.title
    refs.descriptionInput.value = promotion.description
    refs.termsInput.value = promotion.terms ?? ''
    refs.startsAtInput.value = toVenueLocalInput(promotion.startsAt, timezone)
    refs.endsAtInput.value = toVenueLocalInput(promotion.endsAt, timezone)
    refs.formError.textContent = ''
    refs.formError.hidden = true
    refs.formCard.hidden = false
    refs.formCard.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const buildPayload = (): VenuePromotionMutationRequest | null => {
    const title = refs.titleInput.value.trim()
    const description = refs.descriptionInput.value.trim()
    const terms = refs.termsInput.value.trim()
    const startsAt = refs.startsAtInput.value
    const endsAt = refs.endsAtInput.value
    let error = ''
    if (!title) {
      error = 'Введите название акции.'
    } else if (!description) {
      error = 'Введите описание акции.'
    } else if (!startsAt || !endsAt) {
      error = 'Укажите начало и окончание акции.'
    } else if (startsAt >= endsAt) {
      error = 'Начало акции должно быть раньше окончания.'
    }
    refs.formError.textContent = error
    refs.formError.hidden = !error
    if (error) return null
    return { title, description, terms: terms || null, startsAt, endsAt }
  }

  const setMutationState = (pending: boolean) => {
    mutationPending = pending
    refs.createButton.disabled = pending
    refs.saveButton.disabled = pending
    refs.cancelButton.disabled = pending
    refs.list.querySelectorAll('button').forEach((button) => {
      ;(button as HTMLButtonElement).disabled = pending
    })
  }

  const renderCard = (promotion: VenuePromotionDto) => {
    const card = el('article', { className: 'card venue-promotion-card' })
    const heading = el('div', { className: 'venue-promotion-card-heading' })
    append(
      heading,
      el('h4', { text: promotion.title }),
      el('span', { className: 'venue-promotion-status', text: statusLabel(promotion.status) })
    )
    const period = el('p', {
      className: 'venue-order-sub',
      text: `${formatDateTime(promotion.startsAt, timezone)} — ${formatDateTime(promotion.endsAt, timezone)}`
    })
    const description = el('p', { text: promotion.description })
    append(card, heading, period, description)
    if (promotion.terms?.trim()) {
      card.appendChild(el('p', { className: 'venue-promotion-terms', text: `Условия: ${promotion.terms.trim()}` }))
    }
    if (promotion.status !== 'ARCHIVED') {
      const actions = el('div', { className: 'venue-inline-actions' })
      const editButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Редактировать'
      }) as HTMLButtonElement
      actions.appendChild(editButton)
      cardDisposables.push(on(editButton, 'click', () => openEditForm(promotion)))

      const nextStatus = promotion.status === 'ACTIVE' ? 'PAUSED' : 'ACTIVE'
      const statusButton = el('button', {
        className: 'button-secondary button-small',
        text: promotion.status === 'ACTIVE' ? 'Приостановить' : 'Опубликовать'
      }) as HTMLButtonElement
      actions.appendChild(statusButton)
      cardDisposables.push(
        on(statusButton, 'click', () => {
          void changeStatus(promotion, nextStatus, statusButton)
        })
      )

      const archiveButton = el('button', {
        className: 'button-danger button-small',
        text: 'Архивировать'
      }) as HTMLButtonElement
      actions.appendChild(archiveButton)
      cardDisposables.push(on(archiveButton, 'click', () => void archive(promotion, archiveButton)))
      card.appendChild(actions)
    }
    return card
  }

  const renderList = () => {
    cardDisposables.splice(0).forEach((dispose) => dispose())
    refs.list.replaceChildren()
    refs.empty.hidden = items.length > 0
    groupPromotions(items).forEach((group) => {
      const section = el('section', { className: 'venue-promotion-group' })
      section.dataset.group = group.key
      section.appendChild(el('h3', { text: group.title }))
      const cards = el('div', { className: 'venue-promotion-list' })
      group.items.forEach((promotion) => cards.appendChild(renderCard(promotion)))
      section.appendChild(cards)
      refs.list.appendChild(section)
    })
    setMutationState(mutationPending)
  }

  async function load() {
    if (inFlight || disposed) return
    if (!canManage) {
      refs.status.textContent = 'Раздел акций доступен владельцу или менеджеру.'
      refs.createButton.hidden = true
      return
    }
    inFlight = true
    refs.status.textContent = 'Загрузка...'
    hideError()
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetPromotions(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadAbort !== controller) return
    inFlight = false
    loadAbort = null
    if (!result.ok) {
      refs.status.textContent = ''
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void load())
      }
      return
    }
    timezone = result.data.timezone || timezone
    items = result.data.items
    refs.status.textContent = ''
    renderList()
  }

  async function save() {
    if (mutationPending) return
    const payload = buildPayload()
    if (!payload) return
    setMutationState(true)
    hideError()
    refs.status.textContent = editingId == null ? 'Создаём черновик...' : 'Сохраняем...'
    mutationAbort?.abort()
    const controller = new AbortController()
    mutationAbort = controller
    const result =
      editingId == null
        ? await venueCreatePromotion(backendUrl, venueId, payload, deps, controller.signal)
        : await venueUpdatePromotion(backendUrl, venueId, editingId, payload, deps, controller.signal)
    if (disposed || mutationAbort !== controller) return
    mutationAbort = null
    setMutationState(false)
    if (!result.ok) {
      refs.status.textContent = ''
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void save())
      }
      return
    }
    const wasCreate = editingId == null
    resetForm()
    await loadAfterMutation(wasCreate ? 'Черновик акции создан.' : 'Изменения сохранены.')
  }

  async function changeStatus(
    promotion: VenuePromotionDto,
    status: 'ACTIVE' | 'PAUSED',
    button: HTMLButtonElement
  ) {
    if (mutationPending) return
    setMutationState(true)
    button.textContent = status === 'ACTIVE' ? 'Публикуем...' : 'Приостанавливаем...'
    hideError()
    mutationAbort?.abort()
    const controller = new AbortController()
    mutationAbort = controller
    const result = await venueSetPromotionStatus(
      backendUrl,
      venueId,
      promotion.id,
      { status },
      deps,
      controller.signal
    )
    if (disposed || mutationAbort !== controller) return
    mutationAbort = null
    setMutationState(false)
    if (!result.ok) {
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void changeStatus(promotion, status, button))
      }
      renderList()
      return
    }
    await loadAfterMutation(status === 'ACTIVE' ? 'Акция опубликована.' : 'Акция приостановлена.')
  }

  async function archive(promotion: VenuePromotionDto, button: HTMLButtonElement) {
    if (mutationPending || !window.confirm(`Архивировать акцию «${promotion.title}»?`)) return
    setMutationState(true)
    button.textContent = 'Архивируем...'
    hideError()
    mutationAbort?.abort()
    const controller = new AbortController()
    mutationAbort = controller
    const result = await venueArchivePromotion(backendUrl, venueId, promotion.id, deps, controller.signal)
    if (disposed || mutationAbort !== controller) return
    mutationAbort = null
    setMutationState(false)
    if (!result.ok) {
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void archive(promotion, button))
      }
      renderList()
      return
    }
    if (editingId === promotion.id) resetForm()
    await loadAfterMutation('Акция архивирована.')
  }

  async function loadAfterMutation(successMessage: string) {
    inFlight = false
    await load()
    if (!refs.error.hidden) return
    refs.status.textContent = successMessage
  }

  const disposables = [
    on(refs.createButton, 'click', openCreateForm),
    on(refs.cancelButton, 'click', resetForm),
    on(refs.saveButton, 'click', () => void save())
  ]

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    mutationAbort?.abort()
    cardDisposables.splice(0).forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
