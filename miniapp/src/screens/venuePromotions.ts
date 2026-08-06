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
  VenuePromotionMenuCategoryDto,
  VenuePromotionMenuItemDto,
  VenuePromotionMutationRequest,
  VenuePromotionStatus,
  VenuePromotionTemplateType,
  VenuePromotionTargetDto,
  VenuePromotionWeekdayWindowDto
} from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'

const TITLE_MAX_LENGTH = 80
const DESCRIPTION_MAX_LENGTH = 1000
const TERMS_MAX_LENGTH = 1000
const DEFAULT_WEEKDAY_WINDOW: VenuePromotionWeekdayWindowDto = {
  weekday: 1,
  startLocal: '12:00',
  endLocal: '18:00'
}
const WEEKDAYS = [
  { value: 1, title: 'Понедельник', rangeTitle: 'понедельник' },
  { value: 2, title: 'Вторник', rangeTitle: 'вторник' },
  { value: 3, title: 'Среда', rangeTitle: 'среда' },
  { value: 4, title: 'Четверг', rangeTitle: 'четверг' },
  { value: 5, title: 'Пятница', rangeTitle: 'пятница' },
  { value: 6, title: 'Суббота', rangeTitle: 'суббота' },
  { value: 7, title: 'Воскресенье', rangeTitle: 'воскресенье' }
] as const

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
  templateTypeSelect: HTMLSelectElement
  ruleFields: HTMLElement
  timezoneHint: HTMLParagraphElement
  windowsList: HTMLDivElement
  addWindowButton: HTMLButtonElement
  targetTypeSelect: HTMLSelectElement
  targetValueSelect: HTMLSelectElement
  discountField: HTMLElement
  discountPercentInput: HTMLInputElement
  giftFields: HTMLElement
  rewardModeSelect: HTMLSelectElement
  fixedRewardField: HTMLElement
  fixedRewardSelect: HTMLSelectElement
  allowlistField: HTMLElement
  rewardAllowlist: HTMLDivElement
  ruleSummary: HTMLDivElement
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

type PromotionLoadResult =
  | { ok: true }
  | { ok: false; error?: ApiErrorInfo }

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
    text: 'Создавайте информационные акции, скидки по расписанию и подарки при покупке.'
  })
  const notice = el('p', {
    className: 'venue-promotion-notice',
    text: 'Акция носит информационный характер. Скидки и промокоды автоматически к заказу не применяются.'
  })
  const happyHoursNotice = el('p', {
    className: 'venue-promotion-notice',
    text: 'Для «Счастливых часов» скидка рассчитывается сервером по актуальным ценам при оформлении заказа.'
  })
  const giftNotice = el('p', {
    className: 'venue-promotion-notice',
    text: 'Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.'
  })
  const createButton = el('button', { text: 'Создать акцию' }) as HTMLButtonElement
  append(header, title, subtitle, notice, happyHoursNotice, giftNotice, createButton)

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
  const templateTypeSelect = document.createElement('select')
  appendSelectOption(templateTypeSelect, 'TEXT_ONLY', 'Информационная акция')
  appendSelectOption(templateTypeSelect, 'HAPPY_HOURS_PERCENT', 'Счастливые часы — скидка %')
  appendSelectOption(templateTypeSelect, 'GIFT_WITH_ITEM', 'Подарок при покупке')
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
  const ruleFields = el('section', { className: 'venue-promotion-rule-fields' })
  ruleFields.hidden = true
  const timezoneHint = el('p', {
    className: 'venue-promotion-timezone',
    text: ''
  }) as HTMLParagraphElement
  const windowsHeading = el('div', { className: 'venue-promotion-rule-heading' })
  const addWindowButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Добавить окно'
  }) as HTMLButtonElement
  append(windowsHeading, el('span', { className: 'field-label', text: 'Дни недели и временные окна' }), addWindowButton)
  const windowsList = el('div', { className: 'venue-promotion-windows' }) as HTMLDivElement
  const targetTypeSelect = document.createElement('select')
  appendSelectOption(targetTypeSelect, 'MENU_CATEGORY', 'Категория')
  appendSelectOption(targetTypeSelect, 'MENU_ITEM', 'Конкретная позиция')
  const targetValueSelect = document.createElement('select')
  const discountPercentInput = document.createElement('input')
  discountPercentInput.type = 'number'
  discountPercentInput.min = '1'
  discountPercentInput.max = '100'
  discountPercentInput.step = '1'
  discountPercentInput.inputMode = 'numeric'
  discountPercentInput.placeholder = 'Например, 50'
  const discountField = buildField('Скидка, %', discountPercentInput)
  discountField.appendChild(
    el('small', {
      text: 'Скидка рассчитывается автоматически по актуальным ценам при оформлении заказа.'
    })
  )
  const giftFields = el('section', { className: 'venue-promotion-gift-fields' })
  giftFields.hidden = true
  const rewardModeSelect = document.createElement('select')
  appendSelectOption(rewardModeSelect, 'FIXED_ITEM', 'Конкретный подарок')
  appendSelectOption(rewardModeSelect, 'CHOICE_ITEMS', 'Подарок на выбор')
  rewardModeSelect.setAttribute('aria-label', 'Тип подарка')
  const fixedRewardSelect = document.createElement('select')
  fixedRewardSelect.setAttribute('aria-label', 'Подарок')
  const fixedRewardField = buildField('Подарок', fixedRewardSelect)
  const rewardAllowlist = el('div', {
    className: 'venue-promotion-reward-allowlist'
  }) as HTMLDivElement
  rewardAllowlist.setAttribute('role', 'group')
  rewardAllowlist.setAttribute('aria-label', 'Разрешённые подарки')
  const allowlistField = buildField('Разрешённые подарки', rewardAllowlist, 'Выберите одну или несколько позиций.')
  append(
    giftFields,
    buildField('Тип подарка', rewardModeSelect),
    fixedRewardField,
    allowlistField,
    el('p', {
      className: 'venue-promotion-rule-helper',
      text: 'Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.'
    })
  )
  const ruleSummary = el('div', { className: 'venue-promotion-rule-summary' }) as HTMLDivElement
  append(
    ruleFields,
    timezoneHint,
    windowsHeading,
    windowsList,
    buildField('Скидка действует на', targetTypeSelect),
    buildField('Категория или позиция', targetValueSelect),
    discountField,
    giftFields,
    el('p', {
      className: 'venue-promotion-rule-helper',
      text: 'Расписание применяется в часовом поясе заведения.'
    }),
    ruleSummary
  )
  const formError = el('p', { className: 'field-error', text: '' }) as HTMLParagraphElement
  formError.hidden = true
  const saveButton = el('button', { text: 'Сохранить черновик' }) as HTMLButtonElement
  const cancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const actions = el('div', { className: 'button-row' })
  append(actions, saveButton, cancelButton)
  append(
    formCard,
    formTitle,
    buildField('Тип акции', templateTypeSelect),
    buildField('Название акции', titleInput),
    buildField('Описание', descriptionInput),
    buildField('Условия', termsInput, 'Необязательно'),
    buildField('Начало', startsAtInput),
    buildField('Окончание', endsAtInput),
    ruleFields,
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
    templateTypeSelect,
    ruleFields,
    timezoneHint,
    windowsList,
    addWindowButton,
    targetTypeSelect,
    targetValueSelect,
    discountField,
    discountPercentInput,
    giftFields,
    rewardModeSelect,
    fixedRewardField,
    fixedRewardSelect,
    allowlistField,
    rewardAllowlist,
    ruleSummary,
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

function appendSelectOption(select: HTMLSelectElement, value: string, label: string) {
  const option = document.createElement('option')
  option.value = value
  option.textContent = label
  select.appendChild(option)
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

function promotionTemplateType(promotion: VenuePromotionDto): VenuePromotionTemplateType | null {
  const templateType = (promotion as VenuePromotionDto & { templateType?: string }).templateType
  if (templateType === 'TEXT_ONLY' || templateType === 'HAPPY_HOURS_PERCENT' || templateType === 'GIFT_WITH_ITEM') {
    return templateType
  }
  return templateType == null ? 'TEXT_ONLY' : null
}

function promotionTemplateLabel(templateType: VenuePromotionTemplateType): string {
  switch (templateType) {
    case 'HAPPY_HOURS_PERCENT':
      return 'Счастливые часы — скидка %'
    case 'GIFT_WITH_ITEM':
      return 'Подарок при покупке'
    case 'TEXT_ONLY':
      return 'Информационная акция'
  }
}

function weekdayTitle(weekday: number): string {
  return WEEKDAYS.find((item) => item.value === weekday)?.title ?? `День ${weekday}`
}

function formatWeekdaySequence(days: number[]): string {
  const sorted = [...new Set(days)].sort((left, right) => left - right)
  const ranges: Array<[number, number]> = []
  sorted.forEach((day) => {
    const last = ranges[ranges.length - 1]
    if (last && day === last[1] + 1) {
      last[1] = day
    } else {
      ranges.push([day, day])
    }
  })
  return ranges
    .map(([start, end], index) => {
      const startDay = WEEKDAYS.find((day) => day.value === start)
      const endDay = WEEKDAYS.find((day) => day.value === end)
      const startTitle = index === 0 ? startDay?.title : startDay?.rangeTitle
      if (start === end) return startTitle ?? weekdayTitle(start)
      return `${startTitle ?? weekdayTitle(start)}–${endDay?.rangeTitle ?? weekdayTitle(end).toLowerCase()}`
    })
    .join(', ')
}

function formatWindowSummary(windows: VenuePromotionWeekdayWindowDto[]): string[] {
  const byTime = new Map<string, number[]>()
  windows.forEach((window) => {
    if (!window.startLocal || !window.endLocal) return
    const key = `${window.startLocal}–${window.endLocal}`
    const days = byTime.get(key) ?? []
    days.push(window.weekday)
    byTime.set(key, days)
  })
  return Array.from(byTime.entries()).map(([time, days]) => `${formatWeekdaySequence(days)}, ${time}`)
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
  let menuCategories: VenuePromotionMenuCategoryDto[] = []
  let menuItems: VenuePromotionMenuItemDto[] = []
  let timezone = 'Europe/Moscow'
  let editingId: number | null = null
  let weekdayWindows: VenuePromotionWeekdayWindowDto[] = [{ ...DEFAULT_WEEKDAY_WINDOW }]
  const cardDisposables: Array<() => void> = []
  const dynamicFormDisposables: Array<() => void> = []

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

  const selectedRewardAllowlistIds = (): number[] =>
    Array.from(refs.rewardAllowlist.querySelectorAll<HTMLInputElement>('input[type="checkbox"]:checked'))
      .map((input) => Number(input.value))
      .filter((value) => Number.isInteger(value) && value > 0)
      .sort((left, right) => left - right)

  const renderRuleSummary = () => {
    const templateType = refs.templateTypeSelect.value as VenuePromotionTemplateType
    refs.ruleSummary.replaceChildren(el('h4', { text: 'Краткое описание' }))
    const windowSummaries = formatWindowSummary(weekdayWindows)
    if (windowSummaries.length) {
      windowSummaries.forEach((summary) => {
        refs.ruleSummary.appendChild(el('p', { text: summary }))
      })
    } else {
      refs.ruleSummary.appendChild(el('p', { text: 'Временные окна не настроены.' }))
    }

    const targetId = Number(refs.targetValueSelect.value)
    if (refs.targetTypeSelect.value === 'MENU_ITEM') {
      const item = menuItems.find((candidate) => candidate.id === targetId)
      refs.ruleSummary.appendChild(
        el('p', {
          text:
            templateType === 'GIFT_WITH_ITEM'
              ? `При заказе: Позиция «${item?.name ?? 'не выбрана'}»`
              : `Позиция: ${item?.name ?? 'не выбрана'}`
        })
      )
    } else {
      const category = menuCategories.find((candidate) => candidate.id === targetId)
      refs.ruleSummary.appendChild(
        el('p', {
          text:
            templateType === 'GIFT_WITH_ITEM'
              ? `При заказе: Категория «${category?.name ?? 'не выбрана'}»`
              : `Категория: ${category?.name ?? 'не выбрана'}`
        })
      )
    }
    if (templateType === 'GIFT_WITH_ITEM') {
      if (refs.rewardModeSelect.value === 'FIXED_ITEM') {
        const item = menuItems.find((candidate) => candidate.id === Number(refs.fixedRewardSelect.value))
        refs.ruleSummary.appendChild(el('p', { text: `Подарок: ${item?.name ?? 'не выбран'}` }))
      } else {
        const selectedNames = selectedRewardAllowlistIds()
          .map((itemId) => menuItems.find((candidate) => candidate.id === itemId)?.name)
          .filter((name): name is string => Boolean(name))
        refs.ruleSummary.appendChild(
          el('p', {
            text: selectedNames.length ? `Подарок: на выбор — ${selectedNames.join(', ')}` : 'Подарок: не выбран'
          })
        )
      }
      refs.ruleSummary.appendChild(el('p', { text: 'Максимум: 1 подарок на заказ' }))
      refs.ruleSummary.appendChild(
        el('small', {
          text: 'Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.'
        })
      )
    } else {
      const discountPercent = Number(refs.discountPercentInput.value)
      refs.ruleSummary.appendChild(
        el('p', {
          text:
            Number.isInteger(discountPercent) && discountPercent >= 1 && discountPercent <= 100
              ? `Скидка: ${discountPercent}%`
              : 'Скидка: не указана'
        })
      )
    }
  }

  const renderTargetOptions = (selectedId?: number | null) => {
    const previousValue =
      selectedId === undefined ? refs.targetValueSelect.value : selectedId == null ? '' : String(selectedId)
    refs.targetValueSelect.replaceChildren()
    const isItemTarget = refs.targetTypeSelect.value === 'MENU_ITEM'
    appendSelectOption(
      refs.targetValueSelect,
      '',
      isItemTarget ? 'Выберите позицию' : 'Выберите категорию'
    )
    if (isItemTarget) {
      menuItems.forEach((item) => {
        const category = menuCategories.find((candidate) => candidate.id === item.categoryId)
        appendSelectOption(
          refs.targetValueSelect,
          String(item.id),
          category ? `${item.name} · ${category.name}` : item.name
        )
      })
    } else {
      menuCategories.forEach((category) => {
        appendSelectOption(refs.targetValueSelect, String(category.id), category.name)
      })
    }
    if (Array.from(refs.targetValueSelect.options).some((option) => option.value === previousValue)) {
      refs.targetValueSelect.value = previousValue
    }
    renderRuleSummary()
  }

  const renderRewardOptions = (fixedItemId?: number | null, allowlistIds?: number[]) => {
    const previousFixedItemId =
      fixedItemId === undefined ? Number(refs.fixedRewardSelect.value) || null : fixedItemId
    const previousAllowlistIds =
      allowlistIds === undefined ? new Set(selectedRewardAllowlistIds()) : new Set(allowlistIds)
    refs.fixedRewardSelect.replaceChildren()
    appendSelectOption(refs.fixedRewardSelect, '', 'Выберите подарок')
    refs.rewardAllowlist.replaceChildren()
    menuItems.forEach((item) => {
      const category = menuCategories.find((candidate) => candidate.id === item.categoryId)
      const label = category ? `${item.name} · ${category.name}` : item.name
      appendSelectOption(refs.fixedRewardSelect, String(item.id), label)
      const checkboxLabel = el('label', { className: 'venue-promotion-reward-option' })
      const checkbox = document.createElement('input')
      checkbox.type = 'checkbox'
      checkbox.value = String(item.id)
      checkbox.checked = previousAllowlistIds.has(item.id)
      append(checkboxLabel, checkbox, el('span', { text: label }))
      refs.rewardAllowlist.appendChild(checkboxLabel)
    })
    if (
      previousFixedItemId != null &&
      Array.from(refs.fixedRewardSelect.options).some((option) => option.value === String(previousFixedItemId))
    ) {
      refs.fixedRewardSelect.value = String(previousFixedItemId)
    }
    renderRuleSummary()
  }

  const syncRewardFields = () => {
    const isFixed = refs.rewardModeSelect.value === 'FIXED_ITEM'
    refs.fixedRewardField.hidden = !isFixed
    refs.allowlistField.hidden = isFixed
    renderRuleSummary()
  }

  const renderWeekdayWindows = () => {
    dynamicFormDisposables.splice(0).forEach((dispose) => dispose())
    refs.windowsList.replaceChildren()
    weekdayWindows.forEach((window, index) => {
      const row = el('div', { className: 'venue-promotion-window' })
      const weekdaySelect = document.createElement('select')
      weekdaySelect.setAttribute('aria-label', `День недели, окно ${index + 1}`)
      WEEKDAYS.forEach((weekday) => {
        appendSelectOption(weekdaySelect, String(weekday.value), weekday.title)
      })
      weekdaySelect.value = String(window.weekday)

      const startInput = document.createElement('input')
      startInput.type = 'time'
      startInput.value = window.startLocal
      startInput.setAttribute('aria-label', `Начало окна ${index + 1}`)
      const endInput = document.createElement('input')
      endInput.type = 'text'
      endInput.inputMode = 'numeric'
      endInput.maxLength = 5
      endInput.placeholder = 'ЧЧ:ММ'
      endInput.value = window.endLocal
      endInput.setAttribute('aria-label', `Окончание окна ${index + 1}`)
      const removeButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Удалить'
      }) as HTMLButtonElement
      removeButton.type = 'button'
      removeButton.setAttribute('aria-label', `Удалить окно ${index + 1}`)
      append(
        row,
        buildField('День', weekdaySelect),
        buildField('С', startInput),
        buildField('До', endInput),
        removeButton
      )
      refs.windowsList.appendChild(row)

      dynamicFormDisposables.push(
        on(weekdaySelect, 'change', () => {
          weekdayWindows[index].weekday = Number(weekdaySelect.value)
          renderRuleSummary()
        }),
        on(startInput, 'input', () => {
          weekdayWindows[index].startLocal = startInput.value
          renderRuleSummary()
        }),
        on(endInput, 'input', () => {
          weekdayWindows[index].endLocal = endInput.value
          renderRuleSummary()
        }),
        on(removeButton, 'click', () => {
          weekdayWindows.splice(index, 1)
          renderWeekdayWindows()
          renderRuleSummary()
        })
      )
    })
    renderRuleSummary()
  }

  const syncTemplateFields = () => {
    const isHappyHours = refs.templateTypeSelect.value === 'HAPPY_HOURS_PERCENT'
    const isGift = refs.templateTypeSelect.value === 'GIFT_WITH_ITEM'
    refs.ruleFields.hidden = !isHappyHours && !isGift
    refs.discountField.hidden = !isHappyHours
    refs.giftFields.hidden = !isGift
    const targetTypeLabel = refs.targetTypeSelect.closest('label')?.querySelector<HTMLElement>('.field-label')
    if (targetTypeLabel) {
      targetTypeLabel.textContent = isGift ? 'При заказе' : 'Скидка действует на'
    }
    refs.timezoneHint.textContent = `Часовой пояс заведения: ${timezone}`
    if (isHappyHours || isGift) {
      syncRewardFields()
      renderRuleSummary()
    }
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
    refs.templateTypeSelect.value = 'TEXT_ONLY'
    weekdayWindows = [{ ...DEFAULT_WEEKDAY_WINDOW }]
    refs.targetTypeSelect.value = 'MENU_CATEGORY'
    refs.discountPercentInput.value = ''
    refs.rewardModeSelect.value = 'FIXED_ITEM'
    renderTargetOptions()
    renderRewardOptions(null, [])
    renderWeekdayWindows()
    syncTemplateFields()
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
    const templateType = promotionTemplateType(promotion)
    if (!templateType) return
    editingId = promotion.id
    refs.formTitle.textContent = 'Редактировать акцию'
    refs.saveButton.textContent = 'Сохранить изменения'
    refs.titleInput.value = promotion.title
    refs.descriptionInput.value = promotion.description
    refs.termsInput.value = promotion.terms ?? ''
    refs.startsAtInput.value = toVenueLocalInput(promotion.startsAt, timezone)
    refs.endsAtInput.value = toVenueLocalInput(promotion.endsAt, timezone)
    refs.templateTypeSelect.value = templateType
    const rule = promotion.rule
    weekdayWindows = rule?.windows?.map((window) => ({ ...window })) ?? []
    refs.targetTypeSelect.value = rule?.target?.type ?? 'MENU_CATEGORY'
    const selectedTargetId =
      rule?.target?.type === 'MENU_ITEM' ? rule.target.menuItemId : rule?.target?.menuCategoryId
    renderTargetOptions(selectedTargetId)
    refs.discountPercentInput.value = rule?.discountPercent == null ? '' : String(rule.discountPercent)
    refs.rewardModeSelect.value = rule?.reward?.mode ?? 'FIXED_ITEM'
    renderRewardOptions(
      rule?.reward?.fixedItem?.menuItemId ?? null,
      rule?.reward?.allowlist?.map((item) => item.menuItemId) ?? []
    )
    renderWeekdayWindows()
    syncTemplateFields()
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
    const templateType = refs.templateTypeSelect.value as VenuePromotionTemplateType
    let error = ''
    if (!title) {
      error = 'Введите название акции.'
    } else if (!description) {
      error = 'Введите описание акции.'
    } else if (!startsAt || !endsAt) {
      error = 'Укажите начало и окончание акции.'
    } else if (startsAt >= endsAt) {
      error = 'Начало акции должно быть раньше окончания.'
    } else if (templateType === 'HAPPY_HOURS_PERCENT' || templateType === 'GIFT_WITH_ITEM') {
      const discountPercent = Number(refs.discountPercentInput.value)
      const targetId = Number(refs.targetValueSelect.value)
      const targetExists =
        refs.targetTypeSelect.value === 'MENU_ITEM'
          ? menuItems.some((item) => item.id === targetId)
          : menuCategories.some((category) => category.id === targetId)
      const startTimePattern = /^(?:[01]\d|2[0-3]):[0-5]\d$/
      const endTimePattern = /^(?:(?:[01]\d|2[0-3]):[0-5]\d|24:00)$/
      const hasInvalidWindow = weekdayWindows.some(
        (window) =>
          !Number.isInteger(window.weekday) ||
          window.weekday < 1 ||
          window.weekday > 7 ||
          !startTimePattern.test(window.startLocal) ||
          !endTimePattern.test(window.endLocal) ||
          window.startLocal >= window.endLocal
      )
      let overlappingDay: number | null = null
      WEEKDAYS.forEach((weekday) => {
        const windowsForDay = weekdayWindows
          .filter((window) => window.weekday === weekday.value)
          .sort((left, right) => left.startLocal.localeCompare(right.startLocal))
        for (let index = 1; index < windowsForDay.length; index += 1) {
          if (windowsForDay[index].startLocal < windowsForDay[index - 1].endLocal) {
            overlappingDay = weekday.value
            break
          }
        }
      })
      if (!weekdayWindows.length) {
        error = 'Добавьте хотя бы одно временное окно.'
      } else if (hasInvalidWindow) {
        error = 'В каждом окне время начала должно быть раньше времени окончания.'
      } else if (overlappingDay != null) {
        error = `Окна в один день не должны пересекаться: ${weekdayTitle(overlappingDay)}.`
      } else if (!targetExists) {
        error =
          refs.targetTypeSelect.value === 'MENU_ITEM'
            ? 'Выберите позицию меню.'
            : 'Выберите категорию меню.'
      } else if (
        templateType === 'HAPPY_HOURS_PERCENT' &&
        (!Number.isInteger(discountPercent) || discountPercent < 1 || discountPercent > 100)
      ) {
        error = 'Укажите целый процент скидки от 1 до 100.'
      } else if (templateType === 'GIFT_WITH_ITEM' && refs.rewardModeSelect.value === 'FIXED_ITEM') {
        const fixedRewardItemId = Number(refs.fixedRewardSelect.value)
        if (!menuItems.some((item) => item.id === fixedRewardItemId)) {
          error = 'Выберите позицию подарка.'
        }
      } else if (templateType === 'GIFT_WITH_ITEM') {
        const allowlistIds = selectedRewardAllowlistIds()
        if (!allowlistIds.length || allowlistIds.some((itemId) => !menuItems.some((item) => item.id === itemId))) {
          error = 'Выберите хотя бы одну разрешённую позицию подарка.'
        }
      }
    }
    refs.formError.textContent = error
    refs.formError.hidden = !error
    if (error) return null
    if (templateType === 'TEXT_ONLY') {
      return { title, description, terms: terms || null, startsAt, endsAt, templateType, rule: null }
    }
    const targetId = Number(refs.targetValueSelect.value)
    const target: VenuePromotionTargetDto =
      refs.targetTypeSelect.value === 'MENU_ITEM'
        ? { type: 'MENU_ITEM', menuItemId: targetId }
        : { type: 'MENU_CATEGORY', menuCategoryId: targetId }
    const reward =
      templateType === 'GIFT_WITH_ITEM'
        ? refs.rewardModeSelect.value === 'FIXED_ITEM'
          ? {
              mode: 'FIXED_ITEM' as const,
              fixedMenuItemId: Number(refs.fixedRewardSelect.value),
              allowlistMenuItemIds: []
            }
          : {
              mode: 'CHOICE_ITEMS' as const,
              fixedMenuItemId: null,
              allowlistMenuItemIds: selectedRewardAllowlistIds()
            }
        : null
    return {
      title,
      description,
      terms: terms || null,
      startsAt,
      endsAt,
      templateType,
      rule: {
        windows: weekdayWindows
          .map((window) => ({ ...window }))
          .sort(
            (left, right) =>
              left.weekday - right.weekday ||
              left.startLocal.localeCompare(right.startLocal) ||
              left.endLocal.localeCompare(right.endLocal)
          ),
        target,
        discountPercent: templateType === 'HAPPY_HOURS_PERCENT' ? Number(refs.discountPercentInput.value) : null,
        reward
      }
    }
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
    card.dataset.promotionId = String(promotion.id)
    const isArchived = promotion.status === 'ARCHIVED'
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
    const templateType = promotionTemplateType(promotion)
    card.appendChild(
      el('p', {
        className: 'venue-promotion-template-label',
        text: promotionTemplateLabel(templateType ?? 'TEXT_ONLY')
      })
    )
    if (promotion.terms?.trim()) {
      card.appendChild(el('p', { className: 'venue-promotion-terms', text: `Условия: ${promotion.terms.trim()}` }))
    }
    if (
      (templateType === 'HAPPY_HOURS_PERCENT' || templateType === 'GIFT_WITH_ITEM') &&
      (!isArchived || promotion.rule != null)
    ) {
      const rule = promotion.rule
      const ruleSummary = el('div', { className: 'venue-promotion-card-rule' })
      const windowSummaries = formatWindowSummary(rule?.windows ?? [])
      windowSummaries.forEach((summary) => ruleSummary.appendChild(el('p', { text: summary })))
      const target = rule?.target
      if (target?.type === 'MENU_ITEM') {
        const itemName = target.label ?? menuItems.find((item) => item.id === target.menuItemId)?.name
        ruleSummary.appendChild(
          el('p', {
            text:
              templateType === 'GIFT_WITH_ITEM'
                ? `При заказе: Позиция «${itemName ?? 'не настроена'}»`
                : `Позиция: ${itemName ?? 'не настроена'}`
          })
        )
      } else if (target?.type === 'MENU_CATEGORY') {
        const categoryName =
          target.label ?? menuCategories.find((category) => category.id === target.menuCategoryId)?.name
        ruleSummary.appendChild(
          el('p', {
            text:
              templateType === 'GIFT_WITH_ITEM'
                ? `При заказе: Категория «${categoryName ?? 'не настроена'}»`
                : `Категория: ${categoryName ?? 'не настроена'}`
          })
        )
      } else {
        ruleSummary.appendChild(el('p', { text: 'Категория или позиция: не настроена' }))
      }
      if (templateType === 'GIFT_WITH_ITEM') {
        const reward = rule?.reward
        if (reward?.mode === 'FIXED_ITEM') {
          ruleSummary.appendChild(
            el('p', { text: `Подарок: ${reward.fixedItem?.name ?? 'не настроен'}` })
          )
        } else {
          const rewardNames = reward?.allowlist?.map((item) => item.name).filter(Boolean) ?? []
          ruleSummary.appendChild(
            el('p', {
              text: rewardNames.length ? `Подарок: на выбор — ${rewardNames.join(', ')}` : 'Подарок: не настроен'
            })
          )
        }
        ruleSummary.appendChild(el('p', { text: 'Максимум: 1 подарок на заказ' }))
        ruleSummary.appendChild(
          el('small', {
            text: 'Гость сам выбирает или подтверждает подарок. Подарок автоматически в заказ не добавляется.'
          })
        )
      } else {
        ruleSummary.appendChild(
          el('p', {
            text: rule?.discountPercent == null ? 'Скидка: не настроена' : `Скидка: ${rule.discountPercent}%`
          })
        )
        ruleSummary.appendChild(
          el('small', {
            text: 'Скидка рассчитывается автоматически по актуальным ценам при оформлении заказа.'
          })
        )
      }
      const issues = rule?.validationIssues?.filter(Boolean) ?? []
      if (!isArchived && (!rule?.readyForActivation || issues.length)) {
        ruleSummary.appendChild(
          el('p', {
            className: 'venue-promotion-validation',
            text: !rule?.readyForActivation
                ? issues.length
                  ? `Нужно исправить перед публикацией: ${issues.join('; ')}`
                  : templateType === 'GIFT_WITH_ITEM'
                    ? 'Заполните расписание, условие покупки и подарок перед публикацией.'
                    : 'Заполните расписание, категорию или позицию и процент перед публикацией.'
              : issues.join('; ')
          })
        )
      }
      card.appendChild(ruleSummary)
    }
    if (isArchived) {
      card.appendChild(
        el('p', {
          className: 'venue-order-sub',
          text: 'Акция находится в архиве. Изменения и повторная публикация недоступны.'
        })
      )
    } else {
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

  async function load(force = false): Promise<PromotionLoadResult> {
    if (disposed) return { ok: false }
    if (inFlight && !force) return { ok: false }
    if (!canManage) {
      refs.status.textContent = 'Раздел акций доступен владельцу или менеджеру.'
      refs.createButton.hidden = true
      return { ok: false }
    }
    inFlight = true
    refs.status.textContent = 'Загрузка...'
    hideError()
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetPromotions(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadAbort !== controller) return { ok: false }
    inFlight = false
    loadAbort = null
    if (!result.ok) {
      refs.status.textContent = ''
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void load())
      }
      return { ok: false, error: result.error }
    }
    timezone = result.data.timezone || timezone
    items = result.data.items.filter((promotion) => promotionTemplateType(promotion) != null)
    menuCategories = result.data.menuCategories ?? []
    menuItems = result.data.menuItems ?? []
    refs.timezoneHint.textContent = `Часовой пояс заведения: ${timezone}`
    const editedPromotion = editingId == null ? null : items.find((promotion) => promotion.id === editingId)
    if (editingId != null && (!editedPromotion || editedPromotion.status === 'ARCHIVED')) {
      resetForm()
    } else if (!refs.formCard.hidden) {
      renderTargetOptions()
      renderRewardOptions()
      renderRuleSummary()
    }
    refs.status.textContent = ''
    renderList()
    return { ok: true }
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
    if (!result.ok) {
      setMutationState(false)
      refs.status.textContent = ''
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void save())
      }
      return
    }
    const wasCreate = editingId == null
    resetForm()
    await loadAfterMutation(wasCreate ? 'Черновик акции создан.' : 'Изменения сохранены.')
    setMutationState(false)
  }

  async function refreshAfterLifecycleStale(error: ApiErrorInfo) {
    refs.status.textContent = ''
    const refresh = await load(true)
    if (disposed) return
    if (!refresh.ok) {
      if (refresh.error?.code === REQUEST_ABORTED_CODE) return
      refs.status.textContent = presentApiError(error, { isDebug, scope: 'venue' }).message
      if (refresh.error) {
        showError(refresh.error, () => void refreshAfterLifecycleStale(error))
      } else {
        showError(error, () => void refreshAfterLifecycleStale(error))
      }
      return
    }
    showError(error, () => void refreshAfterLifecycleStale(error))
  }

  async function changeStatus(
    promotion: VenuePromotionDto,
    status: 'ACTIVE' | 'PAUSED',
    button: HTMLButtonElement
  ) {
    if (mutationPending) return
    setMutationState(true)
    refs.status.textContent = ''
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
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.PROMOTION_LIFECYCLE_STALE) {
        await refreshAfterLifecycleStale(result.error)
        setMutationState(false)
        renderList()
        return
      }
      setMutationState(false)
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void changeStatus(promotion, status, button))
      }
      renderList()
      return
    }
    await loadAfterMutation(status === 'ACTIVE' ? 'Акция опубликована.' : 'Акция приостановлена.')
    setMutationState(false)
  }

  async function archive(promotion: VenuePromotionDto, button: HTMLButtonElement) {
    if (mutationPending || !window.confirm(`Архивировать акцию «${promotion.title}»?`)) return
    setMutationState(true)
    refs.status.textContent = ''
    button.textContent = 'Архивируем...'
    hideError()
    mutationAbort?.abort()
    const controller = new AbortController()
    mutationAbort = controller
    const result = await venueArchivePromotion(backendUrl, venueId, promotion.id, deps, controller.signal)
    if (disposed || mutationAbort !== controller) return
    mutationAbort = null
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.PROMOTION_LIFECYCLE_STALE) {
        await refreshAfterLifecycleStale(result.error)
        setMutationState(false)
        renderList()
        return
      }
      setMutationState(false)
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        showError(result.error, () => void archive(promotion, button))
      }
      renderList()
      return
    }
    if (editingId === promotion.id) resetForm()
    await loadAfterMutation('Акция архивирована.')
    setMutationState(false)
  }

  async function loadAfterMutation(successMessage: string) {
    const refresh = await load(true)
    if (!refresh.ok) return
    refs.status.textContent = successMessage
  }

  const disposables = [
    on(refs.createButton, 'click', openCreateForm),
    on(refs.cancelButton, 'click', resetForm),
    on(refs.saveButton, 'click', () => void save()),
    on(refs.templateTypeSelect, 'change', syncTemplateFields),
    on(refs.addWindowButton, 'click', () => {
      weekdayWindows.push({ ...DEFAULT_WEEKDAY_WINDOW })
      renderWeekdayWindows()
    }),
    on(refs.targetTypeSelect, 'change', () => renderTargetOptions(null)),
    on(refs.targetValueSelect, 'change', renderRuleSummary),
    on(refs.discountPercentInput, 'input', renderRuleSummary),
    on(refs.rewardModeSelect, 'change', syncRewardFields),
    on(refs.fixedRewardSelect, 'change', renderRuleSummary),
    on(refs.rewardAllowlist, 'change', renderRuleSummary)
  ]

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    mutationAbort?.abort()
    cardDisposables.splice(0).forEach((dispose) => dispose())
    dynamicFormDisposables.splice(0).forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
