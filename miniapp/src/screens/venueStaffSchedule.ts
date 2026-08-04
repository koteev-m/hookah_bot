import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueBatchStaffScheduleShifts,
  venueCancelStaffScheduleShift,
  venueGetMyStaffSchedule,
  venueGetStaffProfiles,
  venueGetStaffSchedule,
  venueRestoreStaffScheduleShift,
  venueUpdateStaffScheduleShift
} from '../shared/api/venueApi'
import type {
  VenueAccessDto,
  VenueStaffProfileDto,
  VenueStaffProfileSubtype,
  VenueStaffScheduleAdminShiftDto,
  VenueStaffScheduleBatchAssignmentRequest,
  VenueStaffScheduleBatchOperation,
  VenueStaffScheduleColleagueDto,
  VenueStaffScheduleComputedStatus,
  VenueStaffScheduleConfirmationState,
  VenueStaffScheduleEffectiveHoursDto,
  VenueStaffScheduleOwnShiftDto
} from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueStaffScheduleOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  onOpenStaffModuleSettings?: () => void
}

type AdminFormRefs = {
  addButton: HTMLButtonElement
  formCard: HTMLElement
  formTitle: HTMLHeadingElement
  profileField: HTMLElement
  profileSelect: HTMLSelectElement
  bulkControls: HTMLElement
  profileOptions: HTMLDivElement
  selectedAssignments: HTMLDivElement
  applyCommonTimeButton: HTMLButtonElement
  dateInput: HTMLInputElement
  startInput: HTMLInputElement
  endInput: HTMLInputElement
  effectiveHoursStatus: HTMLParagraphElement
  manualTimeButton: HTMLButtonElement
  applyHoursButton: HTMLButtonElement
  overnightHint: HTMLParagraphElement
  previewButton: HTMLButtonElement
  closeFormButton: HTMLButtonElement
  confirmationCard: HTMLElement
  confirmationTitle: HTMLHeadingElement
  confirmationSummary: HTMLDivElement
  confirmButton: HTMLButtonElement
  closeConfirmationButton: HTMLButtonElement
}

type ScheduleRefs = {
  title: HTMLHeadingElement
  weekLabel: HTMLSpanElement
  previousButton: HTMLButtonElement
  currentButton: HTMLButtonElement
  nextButton: HTMLButtonElement
  timezoneCopy: HTMLParagraphElement
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  list: HTMLDivElement
  admin: AdminFormRefs | null
}

type ShiftDraft = {
  staffProfileId: number
  shiftDate: string
  startsAt: string
  endsAt: string
}

type ShiftTimeSource = 'AUTO' | 'MANUAL'
type EffectiveHoursLookupState = 'LOADING' | 'ERROR'

type BulkAssignmentDraft = {
  staffProfileId: number
  startsAt: string
  endsAt: string
  timeSource: 'COMMON' | 'MANUAL' | 'RESTORE_SAVED'
  operation: VenueStaffScheduleBatchOperation | null
  existingShift: VenueStaffScheduleAdminShiftDto | null
  conflict: string | null
}

type PendingConfirmation =
  | { kind: 'batch'; assignments: VenueStaffScheduleBatchAssignmentRequest[] }
  | { kind: 'update'; shift: VenueStaffScheduleAdminShiftDto; draft: ShiftDraft }
  | { kind: 'restore'; shift: VenueStaffScheduleAdminShiftDto; draft: ShiftDraft }
  | { kind: 'cancel'; shift: VenueStaffScheduleAdminShiftDto }

const STALE_COPY = 'График изменился. Обновите данные и повторите действие.'
const DAY_MS = 24 * 60 * 60 * 1000
const MAX_BATCH_ASSIGNMENTS = 50

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function parseIsoDate(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!match) return null
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  const date = new Date(Date.UTC(year, month - 1, day))
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    return null
  }
  return date
}

function toIsoDate(date: Date): string {
  return [
    String(date.getUTCFullYear()).padStart(4, '0'),
    String(date.getUTCMonth() + 1).padStart(2, '0'),
    String(date.getUTCDate()).padStart(2, '0')
  ].join('-')
}

function addIsoDays(value: string, days: number): string {
  const date = parseIsoDate(value)
  if (!date) return value
  return toIsoDate(new Date(date.getTime() + days * DAY_MS))
}

function startOfIsoWeek(value: string): string {
  const date = parseIsoDate(value)
  if (!date) return value
  const weekday = date.getUTCDay() || 7
  return addIsoDays(value, 1 - weekday)
}

function bootstrapUtcDate(): string {
  const now = new Date()
  return toIsoDate(new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate())))
}

function formatDate(value: string, withWeekday = false): string {
  const date = parseIsoDate(value)
  if (!date) return value
  return new Intl.DateTimeFormat('ru-RU', {
    timeZone: 'UTC',
    ...(withWeekday ? { weekday: 'short' as const } : {}),
    day: '2-digit',
    month: '2-digit',
    year: 'numeric'
  }).format(date)
}

function formatWeekRange(from: string): string {
  return `${formatDate(from)} — ${formatDate(addIsoDays(from, 6))}`
}

function formatProfileSubtype(subtype: VenueStaffProfileSubtype): string {
  switch (subtype) {
    case 'hookah_master':
      return 'Кальянный мастер'
    case 'waiter':
      return 'Официант'
    case 'admin':
      return 'Администратор'
    case 'other':
      return 'Сотрудник'
    default:
      return subtype
  }
}

function profileRoleLabel(profile: Pick<VenueStaffProfileDto, 'roleLabel' | 'subtype'>): string {
  return profile.roleLabel?.trim() || formatProfileSubtype(profile.subtype)
}

function profileLinkageLabel(profile: Pick<VenueStaffProfileDto, 'linkageClass'>): string {
  return profile.linkageClass === 'DISPLAY_ONLY' ? 'без привязки' : 'привязан к сотруднику'
}

function shiftRoleLabel(
  shift: Pick<VenueStaffScheduleAdminShiftDto, 'roleLabel' | 'subtype'> | VenueStaffScheduleColleagueDto
): string {
  return shift.roleLabel?.trim() || formatProfileSubtype(shift.subtype)
}

function statusLabel(status: VenueStaffScheduleComputedStatus | null | undefined): string {
  switch (status?.toLowerCase()) {
    case 'scheduled':
      return 'Запланирована'
    case 'active':
      return 'Идёт сейчас'
    case 'completed':
      return 'Завершена'
    case 'canceled':
      return 'Отменена'
    default:
      return 'Статус недоступен'
  }
}

function statusTone(status: VenueStaffScheduleComputedStatus | null | undefined): string {
  switch (status?.toLowerCase()) {
    case 'active':
      return 'active'
    case 'completed':
      return 'completed'
    case 'canceled':
      return 'canceled'
    case 'scheduled':
      return 'scheduled'
    default:
      return 'warning'
  }
}

function formatInterval(startsAt: string, endsAt: string, endsNextDay: boolean): string {
  return `${startsAt}–${endsAt}${endsNextDay ? ', следующий день' : ''}`
}

function endsNextDay(startsAt: string, endsAt: string): boolean {
  return Boolean(startsAt && endsAt && endsAt <= startsAt)
}

function renderField(labelText: string, control: HTMLElement, help?: string): HTMLElement {
  const field = el('label', { className: 'venue-schedule-field' })
  field.appendChild(el('span', { className: 'field-label', text: labelText }))
  field.appendChild(control)
  if (help) field.appendChild(el('span', { className: 'field-help', text: help }))
  return field
}

function renderErrorActions(container: HTMLElement, actions: ApiErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = el('button', {
      className: action.kind === 'secondary' ? 'button-secondary' : '',
      text: action.label
    }) as HTMLButtonElement
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildScheduleDom(root: HTMLDivElement, canManage: boolean): ScheduleRefs {
  const wrapper = el('div', { className: 'venue-staff-schedule' })
  const header = el('section', { className: 'card venue-schedule-header' })
  const headingRow = el('div', { className: 'venue-schedule-heading-row' })
  const title = el('h2') as HTMLHeadingElement
  const admin: AdminFormRefs | null = canManage ? buildAdminDom() : null
  append(headingRow, title)
  if (admin) headingRow.appendChild(admin.addButton)

  const weekNavigation = el('div', { className: 'venue-schedule-week-navigation' })
  const previousButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Предыдущая неделя'
  }) as HTMLButtonElement
  const weekLabel = el('span', { className: 'venue-schedule-week-label' }) as HTMLSpanElement
  const nextButton = el('button', {
    className: 'button-secondary button-small',
    text: 'Следующая неделя'
  }) as HTMLButtonElement
  const currentButton = el('button', {
    className: 'button-tertiary button-small',
    text: 'Текущая неделя'
  }) as HTMLButtonElement
  append(weekNavigation, previousButton, weekLabel, nextButton, currentButton)

  const timezoneCopy = el('p', {
    className: 'venue-order-sub venue-schedule-timezone',
    text: 'Часовой пояс заведения загружается…'
  }) as HTMLParagraphElement
  append(header, headingRow, weekNavigation, timezoneCopy)

  const status = el('p', { className: 'status venue-schedule-status' }) as HTMLParagraphElement
  status.setAttribute('aria-live', 'polite')
  const error = el('div', { className: 'error-card venue-schedule-error' }) as HTMLDivElement
  error.hidden = true
  const errorTitle = el('h3') as HTMLHeadingElement
  const errorMessage = el('p') as HTMLParagraphElement
  const errorActions = el('div', { className: 'error-actions' }) as HTMLDivElement
  const errorDetails = el('div') as HTMLDivElement
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-schedule-week' }) as HTMLDivElement
  append(wrapper, header, status, error)
  if (admin) append(wrapper, admin.formCard, admin.confirmationCard)
  wrapper.appendChild(list)
  root.replaceChildren(wrapper)

  return {
    title,
    weekLabel,
    previousButton,
    currentButton,
    nextButton,
    timezoneCopy,
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    list,
    admin
  }
}

function buildAdminDom(): AdminFormRefs {
  const addButton = el('button', { className: 'button-small', text: 'Добавить смену' }) as HTMLButtonElement

  const formCard = el('section', { className: 'card venue-schedule-form' })
  formCard.hidden = true
  const formTitle = el('h3', { text: 'Новая смена' }) as HTMLHeadingElement
  const profileSelect = document.createElement('select')
  profileSelect.className = 'venue-select'
  const profileField = renderField('Сотрудник', profileSelect)
  const dateInput = document.createElement('input')
  dateInput.className = 'venue-input'
  dateInput.type = 'date'
  dateInput.required = true
  const startInput = document.createElement('input')
  startInput.className = 'venue-input'
  startInput.type = 'time'
  startInput.required = true
  const endInput = document.createElement('input')
  endInput.className = 'venue-input'
  endInput.type = 'time'
  endInput.required = true
  const formGrid = el('div', { className: 'venue-schedule-form-grid' })
  append(
    formGrid,
    profileField,
    renderField('Дата начала', dateInput),
    renderField('Начало', startInput),
    renderField('Окончание', endInput)
  )
  const effectiveHoursStatus = el('p', {
    className: 'venue-order-sub venue-schedule-effective-hours',
    text: ''
  }) as HTMLParagraphElement
  effectiveHoursStatus.setAttribute('aria-live', 'polite')
  const manualTimeButton = el('button', {
    className: 'button-tertiary button-small',
    text: 'Указать время вручную'
  }) as HTMLButtonElement
  manualTimeButton.hidden = true
  const applyHoursButton = el('button', {
    className: 'button-tertiary button-small',
    text: 'Заполнить по часам заведения'
  }) as HTMLButtonElement
  applyHoursButton.hidden = true
  const effectiveHoursActions = el('div', { className: 'venue-profile-form-actions' })
  append(effectiveHoursActions, manualTimeButton, applyHoursButton)
  const overnightHint = el('p', {
    className: 'venue-order-sub venue-schedule-overnight-hint',
    text: 'Если окончание не позже начала, смена завершится на следующий день.'
  }) as HTMLParagraphElement
  const bulkControls = el('div', { className: 'venue-schedule-bulk-controls' })
  const profileOptionsTitle = el('h4', { text: 'Сотрудники' })
  const profileOptionsCopy = el('p', {
    className: 'venue-order-sub',
    text: 'Выберите одного или нескольких сотрудников для общей даты.'
  })
  const profileOptions = el('div', {
    className: 'venue-schedule-profile-options'
  }) as HTMLDivElement
  profileOptions.setAttribute('role', 'group')
  profileOptions.setAttribute('aria-label', 'Выбор сотрудников')
  const selectedTitle = el('h4', { text: 'Выбранные сотрудники' })
  const applyCommonTimeButton = el('button', {
    className: 'button-tertiary button-small',
    text: 'Применить общее время всем'
  }) as HTMLButtonElement
  const selectedAssignments = el('div', {
    className: 'venue-schedule-selected-assignments'
  }) as HTMLDivElement
  selectedAssignments.setAttribute('aria-live', 'polite')
  append(
    bulkControls,
    profileOptionsTitle,
    profileOptionsCopy,
    profileOptions,
    selectedTitle,
    applyCommonTimeButton,
    selectedAssignments
  )
  const previewButton = el('button', { text: 'Проверить смену' }) as HTMLButtonElement
  const closeFormButton = el('button', {
    className: 'button-secondary',
    text: 'Закрыть'
  }) as HTMLButtonElement
  const formActions = el('div', { className: 'venue-profile-form-actions' })
  append(formActions, previewButton, closeFormButton)
  append(
    formCard,
    formTitle,
    formGrid,
    effectiveHoursStatus,
    effectiveHoursActions,
    overnightHint,
    bulkControls,
    formActions
  )

  const confirmationCard = el('section', { className: 'card venue-schedule-confirmation' })
  confirmationCard.hidden = true
  const confirmationTitle = el('h3') as HTMLHeadingElement
  const confirmationSummary = el('div', { className: 'venue-schedule-confirmation-summary' }) as HTMLDivElement
  const confirmButton = el('button', { text: 'Подтвердить' }) as HTMLButtonElement
  const closeConfirmationButton = el('button', {
    className: 'button-secondary',
    text: 'Вернуться'
  }) as HTMLButtonElement
  const confirmationActions = el('div', { className: 'venue-profile-form-actions' })
  append(confirmationActions, confirmButton, closeConfirmationButton)
  append(confirmationCard, confirmationTitle, confirmationSummary, confirmationActions)

  return {
    addButton,
    formCard,
    formTitle,
    profileField,
    profileSelect,
    bulkControls,
    profileOptions,
    selectedAssignments,
    applyCommonTimeButton,
    dateInput,
    startInput,
    endInput,
    effectiveHoursStatus,
    manualTimeButton,
    applyHoursButton,
    overnightHint,
    previewButton,
    closeFormButton,
    confirmationCard,
    confirmationTitle,
    confirmationSummary,
    confirmButton,
    closeConfirmationButton
  }
}

function warningAllows(shift: VenueStaffScheduleAdminShiftDto, action: string): boolean {
  return shift.warning?.allowedActions.some((item) => item.toUpperCase() === action) === true
}

function canEditShift(shift: VenueStaffScheduleAdminShiftDto): boolean {
  if (shift.warning) return warningAllows(shift, 'UPDATE') || warningAllows(shift, 'REPAIR')
  return (
    shift.computedStatus?.toLowerCase() === 'scheduled' &&
    shift.storedStatus.toLowerCase() === 'scheduled' &&
    !shift.isGuestVisible &&
    !shift.manuallyMarkedActive
  )
}

function canCancelShift(shift: VenueStaffScheduleAdminShiftDto): boolean {
  if (shift.warning) return warningAllows(shift, 'CANCEL')
  const status = shift.computedStatus?.toLowerCase()
  return (status === 'scheduled' || status === 'active') && Boolean(shift.cancelConfirmationState)
}

function canRestoreShift(shift: VenueStaffScheduleAdminShiftDto): boolean {
  return (
    shift.restoreAllowed &&
    (shift.computedStatus?.toLowerCase() === 'canceled' ||
      shift.storedStatus.toLowerCase() === 'canceled')
  )
}

function confirmationStateFor(shift: VenueStaffScheduleAdminShiftDto): VenueStaffScheduleConfirmationState | null {
  if (shift.cancelConfirmationState) return shift.cancelConfirmationState
  if (shift.warning && warningAllows(shift, 'CANCEL')) return 'INVALID_INTERVAL'
  switch (shift.computedStatus?.toLowerCase()) {
    case 'scheduled':
      return 'SCHEDULED'
    case 'active':
      return 'ACTIVE'
    default:
      return null
  }
}

function sameDraft(shift: VenueStaffScheduleAdminShiftDto, draft: ShiftDraft): boolean {
  return (
    shift.staffProfileId === draft.staffProfileId &&
    shift.shiftDate === draft.shiftDate &&
    shift.startsAt === draft.startsAt &&
    shift.endsAt === draft.endsAt
  )
}

export function renderVenueStaffScheduleScreen(options: VenueStaffScheduleOptions) {
  const { root, backendUrl, isDebug, venueId, access, onOpenStaffModuleSettings } = options
  if (!root) return () => undefined

  if (access.teamScheduleModuleEnabled === false) {
    const card = el('section', { className: 'card venue-module-disabled' })
    append(
      card,
      el('h2', { text: access.role === 'STAFF' ? 'Мои смены' : 'График смен' }),
      el('p', {
        className: 'venue-order-sub',
        text: 'Карточки команды и график смен отключены. Сохранённые смены не удалены и снова появятся после включения модуля.'
      })
    )
    if (access.permissions.includes('STAFF_MODULE_SETTINGS_MANAGE') && onOpenStaffModuleSettings) {
      const enableButton = el('button', {
        className: 'button-small button-secondary',
        text: 'Включить в настройках'
      }) as HTMLButtonElement
      enableButton.addEventListener('click', onOpenStaffModuleSettings)
      card.appendChild(enableButton)
    }
    root.replaceChildren(card)
    return () => root.replaceChildren()
  }

  const canManage = access.permissions.includes('STAFF_SCHEDULE_MANAGE')
  const canViewAll = access.permissions.includes('STAFF_SCHEDULE_VIEW')
  const canViewOwn = access.permissions.includes('STAFF_SCHEDULE_VIEW_OWN')
  const isStaffView = !canManage && !canViewAll && canViewOwn
  const refs = buildScheduleDom(root, canManage)
  const deps = buildApiDeps(isDebug)

  refs.title.textContent = isStaffView ? 'Мои смены' : 'График смен'

  let disposed = false
  let loadAbort: AbortController | null = null
  let mutationAbort: AbortController | null = null
  let effectiveHoursAbort: AbortController | null = null
  let effectiveHoursRequestDate: string | null = null
  let loadSeq = 0
  let mutationSeq = 0
  let effectiveHoursSeq = 0
  let weekStart = startOfIsoWeek(bootstrapUtcDate())
  let venueToday: string | null = null
  let timezone: string | null = null
  let venueName = access.venueName?.trim() || `Заведение #${venueId}`
  let profiles: VenueStaffProfileDto[] = []
  let adminShifts: VenueStaffScheduleAdminShiftDto[] = []
  let ownShifts: VenueStaffScheduleOwnShiftDto[] = []
  let editingShift: VenueStaffScheduleAdminShiftDto | null = null
  let restoringShift: VenueStaffScheduleAdminShiftDto | null = null
  let pendingConfirmation: PendingConfirmation | null = null
  let mutationPending = false
  let weekExplicitlyChanged = false
  let timeSource: ShiftTimeSource = 'AUTO'
  const bulkAssignments = new Map<number, BulkAssignmentDraft>()
  const effectiveHoursByDate = new Map<string, VenueStaffScheduleEffectiveHoursDto>()
  const effectiveHoursLookupStates = new Map<string, EffectiveHoursLookupState>()
  const shiftsByDate = new Map<string, VenueStaffScheduleAdminShiftDto[]>()

  const weekEnd = () => addIsoDays(weekStart, 6)

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
    refs.errorTitle.textContent = ''
    refs.errorMessage.textContent = ''
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

  const resetEditor = () => {
    editingShift = null
    restoringShift = null
    pendingConfirmation = null
    bulkAssignments.clear()
    const admin = refs.admin
    if (!admin) return
    admin.formCard.hidden = true
    admin.confirmationCard.hidden = true
    admin.confirmationTitle.textContent = ''
    admin.confirmationSummary.replaceChildren()
    admin.profileSelect.disabled = false
    admin.profileField.hidden = false
    admin.bulkControls.hidden = true
    admin.profileSelect.value = profiles[0] ? String(profiles[0].id) : ''
    admin.dateInput.disabled = false
    admin.dateInput.value = ''
    admin.startInput.value = ''
    admin.endInput.value = ''
    admin.effectiveHoursStatus.textContent = ''
    admin.manualTimeButton.hidden = true
    admin.applyHoursButton.hidden = true
    admin.overnightHint.textContent = 'Если окончание не позже начала, смена завершится на следующий день.'
    admin.applyCommonTimeButton.disabled = true
    admin.selectedAssignments.replaceChildren()
    timeSource = 'AUTO'
    renderProfileOptions()
  }

  const showStaleError = (error: ApiErrorInfo) => {
    resetEditor()
    refs.error.dataset.severity = 'warn'
    refs.errorTitle.textContent = 'График изменился'
    refs.errorMessage.textContent = STALE_COPY
    renderErrorActions(refs.errorActions, [
      {
        label: 'Обновить график',
        kind: 'primary',
        onClick: () => void load()
      }
    ])
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const showMutationError = (error: ApiErrorInfo) => {
    if (
      error.code === ApiErrorCodes.STAFF_SHIFT_STALE ||
      error.code === ApiErrorCodes.STAFF_SHIFT_CONFIRMATION_STALE
    ) {
      showStaleError(error)
      return
    }
    const messages: Partial<Record<string, { title: string; message: string }>> = {
      [ApiErrorCodes.STAFF_SHIFT_CANCELED_CONFLICT]: {
        title: 'Смена была отменена',
        message: 'Обновите график и выберите явное восстановление отменённой смены.'
      },
      [ApiErrorCodes.STAFF_SHIFT_DATE_CONFLICT]: {
        title: 'Смена уже существует',
        message: 'У этого сотрудника уже есть смена с такой датой начала. Обновите график.'
      },
      [ApiErrorCodes.STAFF_SHIFT_TODAY_OVERRIDE]: {
        title: '«Сегодня на смене» управляет записью',
        message: '«Сегодня на смене» уже управляет этой записью. Время смены изменить нельзя.'
      },
      [ApiErrorCodes.STAFF_SHIFT_INVALID_INTERVAL]: {
        title: 'Проверьте время смены',
        message: error.message?.trim() || 'Дата или время смены недоступны в часовом поясе заведения.'
      },
      [ApiErrorCodes.STAFF_SHIFT_IMMUTABLE]: {
        title: 'Смену нельзя изменить',
        message: error.message?.trim() || 'Эта смена уже завершена или отменена.'
      }
    }
    const copy = error.code ? messages[error.code] : undefined
    if (!copy) {
      showError(error, () => void submitConfirmation())
      return
    }
    refs.error.dataset.severity = 'warn'
    refs.errorTitle.textContent = copy.title
    refs.errorMessage.textContent = copy.message
    renderErrorActions(refs.errorActions, [
      { label: 'Обновить график', kind: 'primary', onClick: () => void load() }
    ])
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const updateWeekControls = () => {
    refs.weekLabel.textContent = formatWeekRange(weekStart)
    if (!venueToday) {
      refs.previousButton.disabled = false
      refs.nextButton.disabled = false
      refs.currentButton.disabled = true
      return
    }
    const minDate = addIsoDays(venueToday, -30)
    const maxDate = addIsoDays(venueToday, 90)
    refs.previousButton.disabled = addIsoDays(weekStart, -7) < minDate
    refs.nextButton.disabled = addIsoDays(weekStart, 13) > maxDate
    refs.currentButton.disabled = weekStart === startOfIsoWeek(venueToday)
  }

  const sortedProfiles = () =>
    profiles.slice().sort((left, right) => left.displayName.localeCompare(right.displayName, 'ru'))

  const profileNameFor = (profileId: number): string =>
    profiles.find((profile) => profile.id === profileId)?.displayName || `Профиль #${profileId}`

  const invalidateBatchConfirmation = () => {
    if (pendingConfirmation?.kind === 'batch') pendingConfirmation = null
  }

  const existingShiftFor = (
    staffProfileId: number,
    date: string
  ): VenueStaffScheduleAdminShiftDto | null =>
    shiftsByDate.get(date)?.find((shift) => shift.staffProfileId === staffProfileId) ?? null

  const existingShiftConflictCopy = (shift: VenueStaffScheduleAdminShiftDto): string => {
    if (shift.warning?.message) return shift.warning.message
    switch ((shift.computedStatus ?? shift.storedStatus).toLowerCase()) {
      case 'scheduled':
        return 'Смена уже запланирована на эту дату.'
      case 'active':
        return 'Смена на эту дату уже идёт и не может быть назначена повторно.'
      case 'completed':
        return 'Смена на эту дату уже завершена и не может быть изменена.'
      default:
        return 'У сотрудника уже есть смена на эту дату.'
    }
  }

  const reconcileBulkAssignment = (assignment: BulkAssignmentDraft, resetChoice: boolean) => {
    const admin = refs.admin
    if (!admin) return
    const existing = existingShiftFor(assignment.staffProfileId, admin.dateInput.value)
    const previousExistingId = assignment.existingShift?.id ?? null
    assignment.existingShift = existing
    if (!existing) {
      if (assignment.timeSource === 'RESTORE_SAVED') {
        assignment.startsAt = admin.startInput.value
        assignment.endsAt = admin.endInput.value
        assignment.timeSource = 'COMMON'
      }
      assignment.operation = 'CREATE'
      assignment.conflict = null
      return
    }

    const status = (existing.computedStatus ?? existing.storedStatus).toLowerCase()
    if (status === 'canceled') {
      if (resetChoice || previousExistingId !== existing.id) {
        assignment.startsAt = existing.startsAt
        assignment.endsAt = existing.endsAt
        assignment.timeSource = 'RESTORE_SAVED'
        assignment.operation = null
      } else if (assignment.operation !== 'RESTORE') {
        assignment.operation = null
      }
      assignment.conflict = existing.restoreAllowed
        ? null
        : 'Эту отменённую смену нельзя восстановить.'
      return
    }

    assignment.operation = null
    assignment.conflict = existingShiftConflictCopy(existing)
  }

  const reconcileBulkAssignments = (resetChoices: boolean) => {
    bulkAssignments.forEach((assignment) => reconcileBulkAssignment(assignment, resetChoices))
    invalidateBatchConfirmation()
    renderSelectedAssignments()
    renderProfileOptions()
  }

  const renderProfileOptions = () => {
    const admin = refs.admin
    if (!admin) return
    admin.profileOptions.replaceChildren()
    if (!profiles.length) {
      admin.profileOptions.appendChild(
        el('p', { className: 'venue-empty', text: 'Нет доступных карточек сотрудников.' })
      )
      return
    }

    sortedProfiles().forEach((profile) => {
      const option = el('label', { className: 'venue-schedule-profile-option' })
      const checkbox = document.createElement('input')
      checkbox.type = 'checkbox'
      checkbox.checked = bulkAssignments.has(profile.id)
      checkbox.disabled = !checkbox.checked && bulkAssignments.size >= MAX_BATCH_ASSIGNMENTS
      checkbox.setAttribute('aria-label', `Выбрать ${profile.displayName}`)
      const linkage = profileLinkageLabel(profile)
      const copy = el('span', {
        text: `${profile.displayName} · ${profileRoleLabel(profile)} · ${linkage}`
      })
      checkbox.addEventListener('change', () => {
        hideError()
        if (checkbox.checked) {
          if (bulkAssignments.size >= MAX_BATCH_ASSIGNMENTS) {
            checkbox.checked = false
            showToast(`Можно выбрать не больше ${MAX_BATCH_ASSIGNMENTS} сотрудников`)
            return
          }
          const assignment: BulkAssignmentDraft = {
            staffProfileId: profile.id,
            startsAt: admin.startInput.value,
            endsAt: admin.endInput.value,
            timeSource: 'COMMON',
            operation: 'CREATE',
            existingShift: null,
            conflict: null
          }
          reconcileBulkAssignment(assignment, true)
          bulkAssignments.set(profile.id, assignment)
        } else {
          bulkAssignments.delete(profile.id)
        }
        invalidateBatchConfirmation()
        renderProfileOptions()
        renderSelectedAssignments()
      })
      append(option, checkbox, copy)
      admin.profileOptions.appendChild(option)
    })
  }

  const renderSelectedAssignments = () => {
    const admin = refs.admin
    if (!admin) return
    admin.selectedAssignments.replaceChildren()
    admin.applyCommonTimeButton.disabled =
      !bulkAssignments.size || !admin.startInput.value || !admin.endInput.value
    if (!bulkAssignments.size) {
      admin.selectedAssignments.appendChild(
        el('p', { className: 'venue-empty', text: 'Сотрудники пока не выбраны.' })
      )
      return
    }

    const assignments = Array.from(bulkAssignments.values()).sort((left, right) =>
      profileNameFor(left.staffProfileId).localeCompare(profileNameFor(right.staffProfileId), 'ru')
    )
    assignments.forEach((assignment) => {
      const profile = profiles.find((candidate) => candidate.id === assignment.staffProfileId)
      const displayName = profile?.displayName ?? profileNameFor(assignment.staffProfileId)
      const row = el('article', { className: 'venue-schedule-assignment' })
      const heading = el('div', { className: 'venue-schedule-assignment-heading' })
      const identity = el('div')
      append(
        identity,
        el('strong', { text: displayName }),
        el('p', {
          className: 'venue-order-sub',
          text: profile ? profileRoleLabel(profile) : 'Сотрудник'
        })
      )
      const removeButton = el('button', {
        className: 'button-tertiary button-small',
        text: 'Убрать'
      }) as HTMLButtonElement
      removeButton.setAttribute('aria-label', `Убрать ${displayName}`)
      removeButton.addEventListener('click', () => {
        bulkAssignments.delete(assignment.staffProfileId)
        invalidateBatchConfirmation()
        renderProfileOptions()
        renderSelectedAssignments()
      })
      append(heading, identity, removeButton)

      const start = document.createElement('input')
      start.className = 'venue-input'
      start.type = 'time'
      start.required = true
      start.value = assignment.startsAt
      const end = document.createElement('input')
      end.className = 'venue-input'
      end.type = 'time'
      end.required = true
      end.value = assignment.endsAt
      const intervalHint = el('p', {
        className: 'venue-order-sub venue-schedule-assignment-hint'
      }) as HTMLParagraphElement
      const updateHint = () => {
        intervalHint.textContent = start.value && end.value
          ? formatInterval(start.value, end.value, endsNextDay(start.value, end.value))
          : 'Укажите начало и окончание.'
      }
      const updateAssignmentTime = () => {
        assignment.startsAt = start.value
        assignment.endsAt = end.value
        assignment.timeSource = 'MANUAL'
        invalidateBatchConfirmation()
        updateHint()
      }
      start.addEventListener('input', updateAssignmentTime)
      end.addEventListener('input', updateAssignmentTime)
      const timeGrid = el('div', { className: 'venue-schedule-assignment-time' })
      append(
        timeGrid,
        renderField(`Начало — ${displayName}`, start),
        renderField(`Окончание — ${displayName}`, end)
      )
      updateHint()
      append(row, heading, timeGrid, intervalHint)

      const existing = assignment.existingShift
      if (existing && (existing.computedStatus ?? existing.storedStatus).toLowerCase() === 'canceled') {
        row.appendChild(
          el('p', {
            className: 'venue-schedule-assignment-warning',
            text: `Смена на эту дату была отменена. Прежнее время: ${formatInterval(
              existing.startsAt,
              existing.endsAt,
              existing.endsNextDay
            )}.`
          })
        )
        if (existing.restoreAllowed && assignment.operation !== 'RESTORE') {
          const restoreButton = el('button', {
            className: 'button-secondary button-small',
            text: 'Восстановить'
          }) as HTMLButtonElement
          restoreButton.setAttribute('aria-label', `Восстановить ${displayName}`)
          restoreButton.addEventListener('click', () => {
            assignment.operation = 'RESTORE'
            assignment.conflict = null
            invalidateBatchConfirmation()
            renderSelectedAssignments()
          })
          row.appendChild(restoreButton)
        } else if (assignment.operation === 'RESTORE') {
          row.appendChild(
            el('p', { className: 'venue-schedule-assignment-ready', text: 'Будет восстановлена.' })
          )
        }
      } else if (assignment.operation === 'CREATE') {
        row.appendChild(
          el('p', { className: 'venue-schedule-assignment-ready', text: 'Будет создана.' })
        )
      }
      if (assignment.conflict) {
        row.appendChild(
          el('p', { className: 'venue-schedule-assignment-warning', text: assignment.conflict })
        )
      }
      admin.selectedAssignments.appendChild(row)
    })
  }

  const syncCommonAssignments = () => {
    const admin = refs.admin
    if (!admin || admin.bulkControls.hidden) return
    bulkAssignments.forEach((assignment) => {
      if (assignment.timeSource !== 'COMMON') return
      assignment.startsAt = admin.startInput.value
      assignment.endsAt = admin.endInput.value
    })
    invalidateBatchConfirmation()
    renderSelectedAssignments()
  }

  const storeShiftSnapshots = (
    from: string,
    to: string,
    shifts: VenueStaffScheduleAdminShiftDto[]
  ) => {
    let date = from
    while (date <= to) {
      shiftsByDate.set(
        date,
        shifts.filter((shift) => shift.shiftDate === date)
      )
      date = addIsoDays(date, 1)
    }
  }

  const populateProfiles = () => {
    const admin = refs.admin
    if (!admin) return
    const selected = admin.profileSelect.value
    admin.profileSelect.replaceChildren()
    sortedProfiles().forEach((profile) => {
      const linkage = profileLinkageLabel(profile)
      admin.profileSelect.appendChild(
        new Option(
          `${profile.displayName} · ${profileRoleLabel(profile)} · ${linkage}`,
          String(profile.id)
        )
      )
    })
    admin.profileSelect.value = Array.from(admin.profileSelect.options).some(
      (option) => option.value === selected
    )
      ? selected
      : admin.profileSelect.options[0]?.value ?? ''
    renderProfileOptions()
    renderSelectedAssignments()
  }

  const renderAdminShift = (shift: VenueStaffScheduleAdminShiftDto): HTMLElement => {
    const row = el('article', { className: 'venue-schedule-shift' })
    const main = el('div', { className: 'venue-schedule-shift-main' })
    const title = el('strong', { text: shift.displayName })
    const role = el('p', { className: 'venue-order-sub', text: shiftRoleLabel(shift) })
    const interval = el('p', {
      className: 'venue-schedule-interval',
      text: formatInterval(shift.startsAt, shift.endsAt, shift.endsNextDay)
    })
    const status = el('span', {
      className: 'venue-schedule-lifecycle',
      text: statusLabel(shift.computedStatus)
    })
    status.dataset.tone = statusTone(shift.computedStatus)
    append(main, title, role, interval, status)

    if (shift.warning) {
      const warning = el('p', {
        className: 'venue-schedule-row-warning',
        text: shift.warning.message || 'Интервал смены требует проверки.'
      })
      main.appendChild(warning)
    }
    const storedStatus = shift.storedStatus.toLowerCase()
    if (
      shift.isGuestVisible ||
      shift.manuallyMarkedActive ||
      storedStatus === 'active' ||
      storedStatus === 'completed'
    ) {
      main.appendChild(
        el('p', {
          className: 'venue-schedule-today-overlay',
          text: '«Сегодня на смене» управляет общей записью. Время нельзя переносить из графика.'
        })
      )
    }

    const actions = el('div', { className: 'venue-schedule-shift-actions' })
    if (canRestoreShift(shift)) {
      const restoreButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Восстановить'
      }) as HTMLButtonElement
      restoreButton.addEventListener('click', () => openRestoreForm(shift))
      actions.appendChild(restoreButton)
    }
    if (canEditShift(shift)) {
      const editButton = el('button', {
        className: 'button-secondary button-small',
        text: shift.warning ? 'Исправить' : 'Редактировать'
      }) as HTMLButtonElement
      editButton.addEventListener('click', () => openEditForm(shift))
      actions.appendChild(editButton)
    }
    if (canCancelShift(shift)) {
      const cancelButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Отменить'
      }) as HTMLButtonElement
      cancelButton.addEventListener('click', () => openCancelConfirmation(shift))
      actions.appendChild(cancelButton)
    }
    append(row, main)
    if (actions.childElementCount) row.appendChild(actions)
    return row
  }

  const renderColleague = (colleague: VenueStaffScheduleColleagueDto): HTMLElement => {
    const row = el('li', { className: 'venue-schedule-colleague' })
    const copy = `${colleague.displayName} · ${shiftRoleLabel(colleague)} · ${formatInterval(
      colleague.startsAt,
      colleague.endsAt,
      colleague.endsNextDay
    )} · ${statusLabel(colleague.computedStatus)}`
    row.textContent = copy
    return row
  }

  const renderOwnShift = (shift: VenueStaffScheduleOwnShiftDto): HTMLElement => {
    const row = el('article', { className: 'venue-schedule-shift venue-schedule-own-shift' })
    const interval = el('p', {
      className: 'venue-schedule-interval',
      text: formatInterval(shift.startsAt, shift.endsAt, shift.endsNextDay)
    })
    const status = el('span', {
      className: 'venue-schedule-lifecycle',
      text: statusLabel(shift.computedStatus)
    })
    status.dataset.tone = statusTone(shift.computedStatus)
    append(row, el('strong', { text: venueName }), interval, status)
    const colleaguesTitle = el('p', { className: 'venue-order-sub', text: 'Коллеги в этой смене' })
    row.appendChild(colleaguesTitle)
    if (shift.colleagues.length) {
      const colleagues = el('ul', { className: 'venue-schedule-colleagues' })
      shift.colleagues.forEach((colleague) => colleagues.appendChild(renderColleague(colleague)))
      row.appendChild(colleagues)
    } else {
      row.appendChild(
        el('p', {
          className: 'venue-empty',
          text: 'Коллег с пересекающейся сменой нет.'
        })
      )
    }
    return row
  }

  const renderWeek = () => {
    refs.list.replaceChildren()
    for (let index = 0; index < 7; index += 1) {
      const date = addIsoDays(weekStart, index)
      const day = el('section', { className: 'card venue-schedule-day' })
      const dayTitle = el('h3', {
        text: formatDate(date, true)
      })
      const shifts = isStaffView
        ? ownShifts.filter((shift) => shift.shiftDate === date)
        : adminShifts.filter((shift) => shift.shiftDate === date)
      day.appendChild(dayTitle)
      if (!shifts.length) {
        day.appendChild(el('p', { className: 'venue-empty', text: 'Смен нет.' }))
      } else if (isStaffView) {
        ;(shifts as VenueStaffScheduleOwnShiftDto[]).forEach((shift) =>
          day.appendChild(renderOwnShift(shift))
        )
      } else {
        ;(shifts as VenueStaffScheduleAdminShiftDto[]).forEach((shift) =>
          day.appendChild(renderAdminShift(shift))
        )
      }
      refs.list.appendChild(day)
    }

    const total = isStaffView ? ownShifts.length : adminShifts.length
    if (!total) {
      refs.list.prepend(
        el('section', {
          className: 'card venue-schedule-empty',
          text: isStaffView
            ? 'На этой неделе у вас нет смен.'
            : 'На этой неделе смен нет. График необязателен и не блокирует текущую работу заведения.'
        })
      )
    }
  }

  const markHoursRange = (from: string, state: EffectiveHoursLookupState) => {
    for (let index = 0; index < 7; index += 1) {
      effectiveHoursLookupStates.set(addIsoDays(from, index), state)
    }
  }

  const storeEffectiveHours = (
    from: string,
    values: VenueStaffScheduleEffectiveHoursDto[]
  ) => {
    const valuesByDate = new Map(values.map((value) => [value.serviceDate, value]))
    for (let index = 0; index < 7; index += 1) {
      const date = addIsoDays(from, index)
      const value = valuesByDate.get(date)
      if (value) {
        effectiveHoursByDate.set(date, value)
        effectiveHoursLookupStates.delete(date)
      } else {
        effectiveHoursByDate.delete(date)
        effectiveHoursLookupStates.set(date, 'ERROR')
      }
    }
  }

  function syncEffectiveHoursForForm(applyAuto: boolean) {
    const admin = refs.admin
    if (!admin || !admin.dateInput.value) return
    const date = admin.dateInput.value
    const hours = effectiveHoursByDate.get(date)
    const lookupState = effectiveHoursLookupStates.get(date)
    admin.manualTimeButton.hidden = true
    admin.applyHoursButton.hidden = true

    if (!hours) {
      if (applyAuto && timeSource === 'AUTO' && !editingShift) {
        admin.startInput.value = ''
        admin.endInput.value = ''
        updateOvernightHint()
        syncCommonAssignments()
      }
      admin.effectiveHoursStatus.textContent =
        lookupState === 'LOADING'
          ? 'Загружаем часы заведения для выбранной даты…'
          : lookupState === 'ERROR'
            ? 'Не удалось загрузить часы заведения. Время не подставлено; его можно указать вручную.'
            : 'Часы заведения для выбранной даты ещё не загружены.'
      admin.manualTimeButton.hidden = lookupState === 'LOADING'
      return
    }

    if (hours.state === 'OPEN' && hours.opensAt && hours.closesAt) {
      if (applyAuto && timeSource === 'AUTO' && !editingShift) {
        admin.startInput.value = hours.opensAt
        admin.endInput.value = hours.closesAt
        updateOvernightHint()
        syncCommonAssignments()
      }
      admin.effectiveHoursStatus.textContent = `По графику заведения: ${formatInterval(
        hours.opensAt,
        hours.closesAt,
        hours.endsNextDay
      )}.`
      admin.applyHoursButton.hidden = timeSource === 'AUTO' && !editingShift
      return
    }

    if (applyAuto && timeSource === 'AUTO' && !editingShift) {
      admin.startInput.value = ''
      admin.endInput.value = ''
      updateOvernightHint()
      syncCommonAssignments()
    }
    if (hours.state === 'CLOSED') {
      admin.effectiveHoursStatus.textContent = 'По графику заведение закрыто в этот день.'
    } else if (hours.state === 'NOT_CONFIGURED') {
      admin.effectiveHoursStatus.textContent =
        'Часы работы заведения на этот день не настроены. Укажите время смены вручную.'
    } else {
      admin.effectiveHoursStatus.textContent =
        'Часы заведения заполнены некорректно. Время не подставлено; его можно указать вручную.'
    }
    admin.manualTimeButton.hidden = false
  }

  async function loadEffectiveHoursForDate(
    date: string,
    applyAuto = true,
    resetChoices = false
  ): Promise<boolean> {
    const admin = refs.admin
    if (!admin || !date || disposed) return false
    const previousRequestDate = effectiveHoursRequestDate
    effectiveHoursAbort?.abort()
    if (
      previousRequestDate &&
      effectiveHoursLookupStates.get(previousRequestDate) === 'LOADING'
    ) {
      effectiveHoursLookupStates.delete(previousRequestDate)
    }
    effectiveHoursAbort = new AbortController()
    effectiveHoursRequestDate = date
    const controller = effectiveHoursAbort
    const seq = ++effectiveHoursSeq
    effectiveHoursByDate.delete(date)
    effectiveHoursLookupStates.set(date, 'LOADING')
    syncEffectiveHoursForForm(applyAuto)
    const result = await venueGetStaffSchedule(
      backendUrl,
      { venueId, from: date, to: date },
      deps,
      controller.signal
    )
    if (disposed || effectiveHoursAbort !== controller || effectiveHoursSeq !== seq) return false
    effectiveHoursAbort = null
    effectiveHoursRequestDate = null
    if (!result.ok) {
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        effectiveHoursLookupStates.set(date, 'ERROR')
        if (admin.dateInput.value === date) syncEffectiveHoursForForm(applyAuto)
      }
      return false
    }
    storeShiftSnapshots(date, date, result.data.shifts ?? [])
    const hours = result.data.effectiveHours?.find((value) => value.serviceDate === date)
    if (!hours) {
      effectiveHoursLookupStates.set(date, 'ERROR')
    } else {
      effectiveHoursByDate.set(date, hours)
      effectiveHoursLookupStates.delete(date)
    }
    if (result.data.timezone?.trim()) {
      timezone = result.data.timezone
      refs.timezoneCopy.textContent = `Все даты и время указаны в часовом поясе заведения: ${timezone}.`
    }
    if (admin.dateInput.value === date) {
      syncEffectiveHoursForForm(applyAuto)
      if (!editingShift && !restoringShift) reconcileBulkAssignments(resetChoices)
    }
    return true
  }

  const setLoading = () => {
    hideError()
    setStatus('Загрузка графика…')
    refs.list.replaceChildren(
      el('section', { className: 'card venue-schedule-loading', text: 'Загружаем смены…' })
    )
  }

  async function load() {
    if (disposed || (!canViewAll && !canViewOwn)) return
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    setLoading()
    resetEditor()
    mutationAbort?.abort()
    mutationAbort = null
    mutationPending = false
    const from = weekStart
    const to = weekEnd()

    effectiveHoursAbort?.abort()
    effectiveHoursAbort = null
    effectiveHoursRequestDate = null
    effectiveHoursSeq += 1
    effectiveHoursByDate.clear()
    effectiveHoursLookupStates.clear()
    shiftsByDate.clear()
    if (!isStaffView) markHoursRange(from, 'LOADING')

    if (isStaffView) {
      const result = await venueGetMyStaffSchedule(
        backendUrl,
        { venueId, from, to },
        deps,
        controller.signal
      )
      if (disposed || loadAbort !== controller || loadSeq !== seq) return
      loadAbort = null
      if (!result.ok) {
        if (result.error.code !== REQUEST_ABORTED_CODE) {
          setStatus('')
          showError(result.error, () => void load())
        }
        return
      }
      timezone = result.data.timezone
      venueToday = result.data.venueToday
      venueName = result.data.venueName?.trim() || venueName
      const authoritativeWeek = startOfIsoWeek(venueToday)
      if (!weekExplicitlyChanged && authoritativeWeek !== weekStart) {
        weekStart = authoritativeWeek
        updateWeekControls()
        void load()
        return
      }
      ownShifts = result.data.shifts.filter(
        (shift) => shift.shiftDate >= from && shift.shiftDate <= to
      )
    } else {
      const [scheduleResult, profilesResult] = await Promise.all([
        venueGetStaffSchedule(backendUrl, { venueId, from, to }, deps, controller.signal),
        venueGetStaffProfiles(backendUrl, venueId, deps, controller.signal)
      ])
      if (disposed || loadAbort !== controller || loadSeq !== seq) return
      loadAbort = null
      const failed = !scheduleResult.ok ? scheduleResult : !profilesResult.ok ? profilesResult : null
      if (failed && !failed.ok) {
        if (failed.error.code !== REQUEST_ABORTED_CODE) {
          markHoursRange(from, 'ERROR')
          syncEffectiveHoursForForm(true)
          setStatus('')
          showError(failed.error, () => void load())
        }
        return
      }
      if (!scheduleResult.ok || !profilesResult.ok) return
      timezone = scheduleResult.data.timezone
      venueToday = scheduleResult.data.venueToday
      venueName = scheduleResult.data.venueName?.trim() || venueName
      const authoritativeWeek = startOfIsoWeek(venueToday)
      if (!weekExplicitlyChanged && authoritativeWeek !== weekStart) {
        weekStart = authoritativeWeek
        updateWeekControls()
        void load()
        return
      }
      adminShifts = scheduleResult.data.shifts.filter(
        (shift) => shift.shiftDate >= from && shift.shiftDate <= to
      )
      storeShiftSnapshots(from, to, adminShifts)
      storeEffectiveHours(from, scheduleResult.data.effectiveHours ?? [])
      profiles = profilesResult.data.profiles
      populateProfiles()
      syncEffectiveHoursForForm(true)
    }

    refs.timezoneCopy.textContent = timezone
      ? `Все даты и время указаны в часовом поясе заведения: ${timezone}.`
      : 'Часовой пояс заведения не загружен.'
    updateWeekControls()
    renderWeek()
    setStatus('График обновлён.')
  }

  const setWeek = (nextStart: string, explicit: boolean) => {
    if (mutationPending) return
    weekStart = nextStart
    weekExplicitlyChanged = explicit
    updateWeekControls()
    void load()
  }

  const openCreateForm = () => {
    const admin = refs.admin
    if (!admin || !canManage) return
    hideError()
    resetEditor()
    admin.formTitle.textContent = 'Добавить смены'
    admin.previewButton.textContent = 'Проверить смены'
    admin.profileField.hidden = true
    admin.bulkControls.hidden = false
    admin.dateInput.min = venueToday ?? ''
    admin.dateInput.max = venueToday ? addIsoDays(venueToday, 90) : ''
    admin.dateInput.value = venueToday && weekStart < venueToday ? venueToday : weekStart
    admin.startInput.value = ''
    admin.endInput.value = ''
    timeSource = 'AUTO'
    updateOvernightHint()
    admin.formCard.hidden = false
    renderProfileOptions()
    renderSelectedAssignments()
    if (
      !effectiveHoursByDate.has(admin.dateInput.value) &&
      !effectiveHoursLookupStates.has(admin.dateInput.value)
    ) {
      void loadEffectiveHoursForDate(admin.dateInput.value, true, true)
    } else {
      syncEffectiveHoursForForm(true)
      reconcileBulkAssignments(true)
    }
    ;(admin.profileOptions.querySelector('input') as HTMLInputElement | null)?.focus()
  }

  function openEditForm(shift: VenueStaffScheduleAdminShiftDto) {
    const admin = refs.admin
    if (!admin || !canManage) return
    hideError()
    resetEditor()
    editingShift = shift
    admin.formTitle.textContent = shift.warning ? 'Исправить смену' : 'Редактировать смену'
    admin.previewButton.textContent = 'Проверить изменения'
    admin.profileField.hidden = false
    admin.bulkControls.hidden = true
    admin.profileSelect.value = String(shift.staffProfileId)
    admin.profileSelect.disabled = true
    admin.dateInput.min = venueToday ?? ''
    admin.dateInput.max = venueToday ? addIsoDays(venueToday, 90) : ''
    admin.dateInput.value = shift.shiftDate
    admin.startInput.value = shift.startsAt
    admin.endInput.value = shift.endsAt
    timeSource = 'MANUAL'
    updateOvernightHint()
    admin.formCard.hidden = false
    if (
      !effectiveHoursByDate.has(admin.dateInput.value) &&
      !effectiveHoursLookupStates.has(admin.dateInput.value)
    ) {
      void loadEffectiveHoursForDate(admin.dateInput.value)
    } else {
      syncEffectiveHoursForForm(false)
    }
    admin.dateInput.focus()
  }

  function openRestoreForm(shift: VenueStaffScheduleAdminShiftDto) {
    const admin = refs.admin
    if (!admin || !canManage || !canRestoreShift(shift)) return
    hideError()
    resetEditor()
    restoringShift = shift
    admin.formTitle.textContent = 'Восстановить смену'
    admin.previewButton.textContent = 'Проверить восстановление'
    admin.profileField.hidden = false
    admin.bulkControls.hidden = true
    admin.profileSelect.value = String(shift.staffProfileId)
    admin.profileSelect.disabled = true
    admin.dateInput.min = shift.shiftDate
    admin.dateInput.max = shift.shiftDate
    admin.dateInput.value = shift.shiftDate
    admin.dateInput.disabled = true
    admin.startInput.value = shift.startsAt
    admin.endInput.value = shift.endsAt
    timeSource = 'MANUAL'
    updateOvernightHint()
    admin.formCard.hidden = false
    if (
      !effectiveHoursByDate.has(shift.shiftDate) &&
      !effectiveHoursLookupStates.has(shift.shiftDate)
    ) {
      void loadEffectiveHoursForDate(shift.shiftDate, false)
    } else {
      syncEffectiveHoursForForm(false)
    }
    admin.startInput.focus()
  }

  function updateOvernightHint() {
    const admin = refs.admin
    if (!admin) return
    admin.overnightHint.textContent = endsNextDay(admin.startInput.value, admin.endInput.value)
      ? `Предпросмотр: ${formatInterval(admin.startInput.value, admin.endInput.value, true)}.`
      : 'Если окончание не позже начала, смена завершится на следующий день.'
  }

  const handleDateChange = () => {
    const admin = refs.admin
    if (!admin || !admin.dateInput.value) return
    invalidateBatchConfirmation()
    if (editingShift) timeSource = 'MANUAL'
    const date = admin.dateInput.value
    if (!effectiveHoursByDate.has(date) && !effectiveHoursLookupStates.has(date)) {
      void loadEffectiveHoursForDate(date, true, true)
      return
    }
    syncEffectiveHoursForForm(true)
    if (!editingShift && !restoringShift) reconcileBulkAssignments(true)
  }

  const markTimesManual = () => {
    timeSource = 'MANUAL'
    updateOvernightHint()
    syncEffectiveHoursForForm(false)
    syncCommonAssignments()
  }

  const enableManualTimes = () => {
    const admin = refs.admin
    if (!admin) return
    timeSource = 'MANUAL'
    syncEffectiveHoursForForm(false)
    admin.startInput.focus()
  }

  const applyVenueHours = () => {
    const admin = refs.admin
    if (!admin) return
    const hours = effectiveHoursByDate.get(admin.dateInput.value)
    if (!hours || hours.state !== 'OPEN' || !hours.opensAt || !hours.closesAt) return
    admin.startInput.value = hours.opensAt
    admin.endInput.value = hours.closesAt
    timeSource = 'AUTO'
    updateOvernightHint()
    syncEffectiveHoursForForm(false)
    syncCommonAssignments()
  }

  const applyCommonTimeToAll = () => {
    const admin = refs.admin
    if (!admin || !admin.startInput.value || !admin.endInput.value) return
    bulkAssignments.forEach((assignment) => {
      assignment.startsAt = admin.startInput.value
      assignment.endsAt = admin.endInput.value
      assignment.timeSource = 'COMMON'
    })
    invalidateBatchConfirmation()
    renderSelectedAssignments()
  }

  const readDraft = (): ShiftDraft | null => {
    const admin = refs.admin
    if (!admin) return null
    if (!timezone?.trim()) {
      showToast('Дождитесь загрузки часового пояса заведения')
      return null
    }
    if (!admin.profileSelect.value || !admin.dateInput.value || !admin.startInput.value || !admin.endInput.value) {
      showToast('Заполните сотрудника, дату и время смены')
      return null
    }
    const staffProfileId = Number(admin.profileSelect.value)
    if (!Number.isInteger(staffProfileId) || staffProfileId <= 0) {
      showToast('Выберите сотрудника')
      return null
    }
    return {
      staffProfileId,
      shiftDate: admin.dateInput.value,
      startsAt: admin.startInput.value,
      endsAt: admin.endInput.value
    }
  }

  const readBatchAssignments = (): VenueStaffScheduleBatchAssignmentRequest[] | null => {
    const admin = refs.admin
    if (!admin) return null
    if (!timezone?.trim()) {
      showToast('Дождитесь загрузки часового пояса заведения')
      return null
    }
    if (!admin.dateInput.value) {
      showToast('Выберите дату смен')
      return null
    }
    if (!bulkAssignments.size) {
      showToast('Выберите хотя бы одного сотрудника')
      return null
    }
    const normalized: VenueStaffScheduleBatchAssignmentRequest[] = []
    for (const assignment of bulkAssignments.values()) {
      const name = profileNameFor(assignment.staffProfileId)
      if (!assignment.startsAt || !assignment.endsAt) {
        showToast(`Укажите время для сотрудника: ${name}`)
        return null
      }
      if (assignment.conflict) {
        showToast(`${name}: ${assignment.conflict}`)
        return null
      }
      if (!assignment.operation) {
        showToast(`${name}: выберите «Восстановить» или уберите сотрудника`)
        return null
      }
      if (assignment.operation === 'RESTORE' && !assignment.existingShift?.updatedAt) {
        showToast(`${name}: обновите данные отменённой смены`)
        return null
      }
      normalized.push({
        staffProfileId: assignment.staffProfileId,
        shiftDate: admin.dateInput.value,
        startsAt: assignment.startsAt,
        endsAt: assignment.endsAt,
        operation: assignment.operation,
        ...(assignment.operation === 'RESTORE'
          ? { expectedUpdatedAt: assignment.existingShift?.updatedAt }
          : {})
      })
    }
    return normalized
  }

  const openDraftConfirmation = async () => {
    const admin = refs.admin
    if (!admin) return

    if (!editingShift && !restoringShift) {
      if (!admin.dateInput.value || !bulkAssignments.size) {
        void readBatchAssignments()
        return
      }
      const checkedDate = admin.dateInput.value
      admin.previewButton.disabled = true
      setStatus('Проверяем смены…')
      const refreshed = await loadEffectiveHoursForDate(checkedDate, true)
      admin.previewButton.disabled = false
      if (disposed || admin.dateInput.value !== checkedDate || editingShift || restoringShift) return
      setStatus('')
      if (!refreshed) {
        showToast('Не удалось проверить смены. Повторите попытку.')
        return
      }
      const assignments = readBatchAssignments()
      if (!assignments) return
      pendingConfirmation = { kind: 'batch', assignments }
      admin.confirmationTitle.textContent = 'Подтвердите назначение смен'
      const summary = el('div', { className: 'venue-schedule-summary-lines' })
      const createCount = assignments.filter((assignment) => assignment.operation === 'CREATE').length
      const restoreCount = assignments.length - createCount
      append(
        summary,
        el('p', { text: `Дата: ${formatDate(checkedDate)}` }),
        el('p', { text: `Будет создано: ${createCount}` }),
        el('p', { text: `Будет восстановлено: ${restoreCount}` })
      )
      assignments.forEach((assignment) => {
        summary.appendChild(
          el('p', {
            text: `${profileNameFor(assignment.staffProfileId)} · ${
              assignment.operation === 'CREATE' ? 'создание' : 'восстановление'
            } · ${formatInterval(
              assignment.startsAt,
              assignment.endsAt,
              endsNextDay(assignment.startsAt, assignment.endsAt)
            )}`
          })
        )
      })
      summary.appendChild(el('p', { text: `Часовой пояс: ${timezone}` }))
      admin.confirmationSummary.replaceChildren(summary)
      admin.confirmButton.textContent = 'Создать смены'
      admin.confirmButton.className = ''
      admin.formCard.hidden = true
      admin.confirmationCard.hidden = false
      return
    }

    const draft = readDraft()
    if (!draft) return
    if (editingShift && sameDraft(editingShift, draft)) {
      showToast('Изменений нет')
      resetEditor()
      return
    }
    const targetShift = editingShift ?? restoringShift
    if (!targetShift) return
    pendingConfirmation = editingShift
      ? { kind: 'update', shift: editingShift, draft }
      : { kind: 'restore', shift: targetShift, draft }
    admin.confirmationTitle.textContent = editingShift
      ? 'Подтвердите изменения'
      : 'Подтвердите восстановление'
    const summary = el('div', { className: 'venue-schedule-summary-lines' })
    append(
      summary,
      el('p', { text: `Сотрудник: ${profileNameFor(draft.staffProfileId)}` }),
      el('p', {
        text: `${editingShift ? 'Было' : 'Отменённая смена'}: ${formatDate(
          targetShift.shiftDate
        )} · ${formatInterval(
          targetShift.startsAt,
          targetShift.endsAt,
          targetShift.endsNextDay
        )}`
      }),
      el('p', {
        text: `${editingShift ? 'Станет' : 'После восстановления'}: ${formatDate(
          draft.shiftDate
        )} · ${formatInterval(
          draft.startsAt,
          draft.endsAt,
          endsNextDay(draft.startsAt, draft.endsAt)
        )}`
      })
    )
    summary.appendChild(el('p', { text: `Часовой пояс: ${timezone}` }))
    admin.confirmationSummary.replaceChildren(summary)
    admin.confirmButton.textContent = editingShift ? 'Сохранить изменения' : 'Восстановить смену'
    admin.confirmButton.className = ''
    admin.formCard.hidden = true
    admin.confirmationCard.hidden = false
  }

  function openCancelConfirmation(shift: VenueStaffScheduleAdminShiftDto) {
    const admin = refs.admin
    if (!admin || !canManage) return
    const state = confirmationStateFor(shift)
    if (!state) return
    hideError()
    resetEditor()
    pendingConfirmation = { kind: 'cancel', shift }
    admin.confirmationTitle.textContent =
      state === 'ACTIVE' ? 'Подтвердите отмену активной смены' : 'Подтвердите отмену смены'
    const summary = el('div', { className: 'venue-schedule-summary-lines' })
    append(
      summary,
      el('p', { text: `Сотрудник: ${shift.displayName}` }),
      el('p', { text: `Дата: ${formatDate(shift.shiftDate)}` }),
      el('p', {
        text: `Время: ${formatInterval(shift.startsAt, shift.endsAt, shift.endsNextDay)}`
      })
    )
    if (state === 'ACTIVE') {
      summary.appendChild(
        el('p', {
          className: 'venue-schedule-active-warning',
          text: 'Смена уже идёт. Отмена завершит её только в плане и требует явного подтверждения.'
        })
      )
    }
    if (shift.isGuestVisible) {
      summary.appendChild(
        el('p', {
          className: 'venue-schedule-active-warning',
          text: 'Сотрудник перестанет отображаться в «Сегодня работают», пока «Сегодня на смене» не будет отмечено снова.'
        })
      )
    }
    admin.confirmationSummary.replaceChildren(summary)
    admin.confirmButton.textContent = 'Отменить смену'
    admin.confirmButton.className = 'button-danger'
    admin.confirmationCard.hidden = false
  }

  const showBatchMutationError = (error: ApiErrorInfo) => {
    const admin = refs.admin
    if (!admin) return
    pendingConfirmation = null
    admin.confirmationCard.hidden = true
    admin.formCard.hidden = false
    refs.error.dataset.severity = 'warn'
    const stale =
      error.code === ApiErrorCodes.STAFF_SHIFT_STALE ||
      error.code === ApiErrorCodes.STAFF_SHIFT_CONFIRMATION_STALE
    refs.errorTitle.textContent = stale ? 'График изменился' : 'Смены не созданы'
    refs.errorMessage.textContent =
      error.message?.trim() ||
      (error.code === ApiErrorCodes.STAFF_SHIFT_CANCELED_CONFLICT
        ? 'Одна из смен была отменена. Выберите её явное восстановление.'
        : 'Пакет отклонён целиком. Проверьте сотрудников и интервалы ещё раз.')
    renderErrorActions(refs.errorActions, [
      {
        label: 'Проверить смены',
        kind: 'primary',
        onClick: () => void openDraftConfirmation()
      }
    ])
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
    const date = admin.dateInput.value
    if (date) void loadEffectiveHoursForDate(date, false)
  }

  async function submitConfirmation() {
    const admin = refs.admin
    const confirmation = pendingConfirmation
    if (!admin || !confirmation || mutationPending || disposed) return
    hideError()
    mutationPending = true
    admin.confirmButton.disabled = true
    admin.closeConfirmationButton.disabled = true
    setStatus(
      confirmation.kind === 'cancel'
        ? 'Отменяем смену…'
        : confirmation.kind === 'batch'
          ? 'Создаём смены…'
          : confirmation.kind === 'restore'
            ? 'Восстанавливаем смену…'
            : 'Сохраняем изменения…'
    )
    mutationAbort?.abort()
    const controller = new AbortController()
    mutationAbort = controller
    const seq = ++mutationSeq

    const result =
      confirmation.kind === 'batch'
        ? await venueBatchStaffScheduleShifts(
            backendUrl,
            { venueId, body: { assignments: confirmation.assignments } },
            deps,
            controller.signal
          )
        : confirmation.kind === 'update'
          ? await venueUpdateStaffScheduleShift(
              backendUrl,
              {
                venueId,
                shiftId: confirmation.shift.id,
                body: {
                  shiftDate: confirmation.draft.shiftDate,
                  startsAt: confirmation.draft.startsAt,
                  endsAt: confirmation.draft.endsAt,
                  expectedUpdatedAt: confirmation.shift.updatedAt
                }
              },
              deps,
              controller.signal
            )
          : confirmation.kind === 'restore'
            ? await venueRestoreStaffScheduleShift(
                backendUrl,
                {
                  venueId,
                  shiftId: confirmation.shift.id,
                  body: {
                    expectedUpdatedAt: confirmation.shift.updatedAt,
                    startsAt: confirmation.draft.startsAt,
                    endsAt: confirmation.draft.endsAt
                  }
                },
                deps,
                controller.signal
              )
          : await venueCancelStaffScheduleShift(
              backendUrl,
              {
                venueId,
                shiftId: confirmation.shift.id,
                body: {
                  expectedUpdatedAt: confirmation.shift.updatedAt,
                  expectedConfirmationState:
                    confirmationStateFor(confirmation.shift) ?? 'SCHEDULED'
                }
              },
              deps,
              controller.signal
            )

    if (disposed || mutationAbort !== controller || mutationSeq !== seq) return
    mutationAbort = null
    mutationPending = false
    admin.confirmButton.disabled = false
    admin.closeConfirmationButton.disabled = false
    if (!result.ok) {
      setStatus('')
      if (result.error.code !== REQUEST_ABORTED_CODE) {
        if (confirmation.kind === 'batch') showBatchMutationError(result.error)
        else showMutationError(result.error)
      }
      return
    }
    const success =
      confirmation.kind === 'batch'
        ? 'Смены созданы'
        : confirmation.kind === 'update'
          ? 'Смена обновлена'
          : confirmation.kind === 'restore'
            ? 'Смена восстановлена'
            : 'Смена отменена'
    resetEditor()
    showToast(success)
    await load()
  }

  const disposables: Array<() => void> = []
  disposables.push(
    on(refs.previousButton, 'click', () => setWeek(addIsoDays(weekStart, -7), true)),
    on(refs.nextButton, 'click', () => setWeek(addIsoDays(weekStart, 7), true)),
    on(refs.currentButton, 'click', () => {
      if (venueToday) setWeek(startOfIsoWeek(venueToday), false)
    })
  )
  if (refs.admin) {
    const admin = refs.admin
    disposables.push(
      on(admin.addButton, 'click', openCreateForm),
      on(admin.closeFormButton, 'click', resetEditor),
      on(admin.previewButton, 'click', () => void openDraftConfirmation()),
      on(admin.dateInput, 'input', handleDateChange),
      on(admin.startInput, 'input', markTimesManual),
      on(admin.endInput, 'input', markTimesManual),
      on(admin.manualTimeButton, 'click', enableManualTimes),
      on(admin.applyHoursButton, 'click', applyVenueHours),
      on(admin.applyCommonTimeButton, 'click', applyCommonTimeToAll),
      on(admin.closeConfirmationButton, 'click', () => {
        if (pendingConfirmation?.kind === 'cancel') {
          resetEditor()
          return
        }
        admin.confirmationCard.hidden = true
        admin.formCard.hidden = false
        pendingConfirmation = null
      }),
      on(admin.confirmButton, 'click', () => void submitConfirmation())
    )
  }

  updateWeekControls()
  if (!canViewAll && !canViewOwn) {
    refs.list.replaceChildren(
      el('section', { className: 'card', text: 'У вас нет доступа к графику смен.' })
    )
  } else {
    void load()
  }

  return () => {
    disposed = true
    loadSeq += 1
    mutationSeq += 1
    loadAbort?.abort()
    mutationAbort?.abort()
    effectiveHoursAbort?.abort()
    loadAbort = null
    mutationAbort = null
    effectiveHoursAbort = null
    effectiveHoursRequestDate = null
    adminShifts = []
    ownShifts = []
    profiles = []
    bulkAssignments.clear()
    effectiveHoursByDate.clear()
    effectiveHoursLookupStates.clear()
    shiftsByDate.clear()
    resetEditor()
    refs.list.replaceChildren()
    disposables.forEach((dispose) => dispose())
  }
}
