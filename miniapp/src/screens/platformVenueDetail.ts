import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  platformAssignOwner,
  platformChangeVenueStatus,
  platformCreateOwnerInvite,
  platformGetSubscription,
  platformGetVenue,
  platformSearchUsers,
  platformUpdatePriceSchedule,
  platformUpdateSubscription
} from '../shared/api/platformApi'
import type {
  PlatformOwnerInviteResponse,
  PlatformPriceScheduleItemDto,
  PlatformSubscriptionSettingsResponse,
  PlatformUserDto,
  PlatformVenueDetailResponse
} from '../shared/api/platformDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type PlatformVenueDetailOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
}

type DetailRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  summaryTitle: HTMLHeadingElement
  summaryInfo: HTMLDivElement
  ownersList: HTMLDivElement
  statusButtons: HTMLDivElement
  userSearch: HTMLInputElement
  userResults: HTMLDivElement
  selectedUserLabel: HTMLParagraphElement
  ownerRoleSelect: HTMLSelectElement
  assignButton: HTMLButtonElement
  inviteTtlInput: HTMLInputElement
  inviteButton: HTMLButtonElement
  inviteResult: HTMLDivElement
  inviteCode: HTMLSpanElement
  inviteExpires: HTMLSpanElement
  inviteInstructions: HTMLParagraphElement
  inviteLink: HTMLAnchorElement
  inviteCopyButton: HTMLButtonElement
  trialEndInput: HTMLInputElement
  paidStartInput: HTMLInputElement
  basePriceInput: HTMLInputElement
  overridePriceInput: HTMLInputElement
  currencyLabel: HTMLSpanElement
  saveSubscriptionButton: HTMLButtonElement
  scheduleList: HTMLDivElement
  addScheduleButton: HTMLButtonElement
  saveScheduleButton: HTMLButtonElement
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

function buildDetailDom(root: HTMLDivElement): DetailRefs {
  const wrapper = el('div', { className: 'venue-settings' })

  const summaryCard = el('div', { className: 'card' })
  const summaryTitle = el('h2', { text: 'Заведение' })
  const summaryInfo = el('div', { className: 'venue-summary' })
  const ownersTitle = el('h3', { text: 'Owners' })
  const ownersList = el('div', { className: 'venue-staff-list' })
  append(summaryCard, summaryTitle, summaryInfo, ownersTitle, ownersList)

  const statusCard = el('div', { className: 'card' })
  const statusTitle = el('h3', { text: 'Статус' })
  const statusButtons = el('div', { className: 'venue-inline-actions' })
  append(statusCard, statusTitle, statusButtons)

  const ownersCard = el('div', { className: 'card' })
  const ownersHeader = el('h3', { text: 'Назначить owner' })
  const searchRow = el('div', { className: 'venue-form-row' })
  const userSearch = document.createElement('input')
  userSearch.className = 'venue-input'
  userSearch.placeholder = 'Поиск пользователя по имени/username'
  const userResults = el('div', { className: 'venue-staff-list' })
  const selectedUserLabel = el('p', { className: 'venue-order-sub', text: 'Пользователь не выбран.' })
  append(searchRow, userSearch)

  const ownerRoleSelect = document.createElement('select')
  ownerRoleSelect.className = 'venue-select'
  ownerRoleSelect.appendChild(new Option('OWNER', 'OWNER'))
  ownerRoleSelect.appendChild(new Option('ADMIN', 'ADMIN'))

  const assignButton = el('button', { text: 'Назначить owner' }) as HTMLButtonElement
  const assignRow = el('div', { className: 'venue-inline-actions' })
  append(assignRow, ownerRoleSelect, assignButton)
  append(ownersCard, ownersHeader, searchRow, selectedUserLabel, userResults, assignRow)

  const inviteCard = el('div', { className: 'card' })
  const inviteTitle = el('h3', { text: 'Owner invite' })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteTtlInput = document.createElement('input')
  inviteTtlInput.className = 'venue-input'
  inviteTtlInput.type = 'number'
  inviteTtlInput.min = '60'
  inviteTtlInput.placeholder = 'TTL (сек), минимум 60'
  const inviteButton = el('button', { text: 'Сгенерировать owner invite' }) as HTMLButtonElement
  append(inviteRow, inviteTtlInput, inviteButton)
  const inviteResult = el('div', { className: 'venue-invite-result' })
  inviteResult.hidden = true
  const inviteCode = el('span')
  const inviteExpires = el('span')
  const inviteInstructions = el('p', { text: '' })
  const inviteLink = document.createElement('a')
  inviteLink.className = 'venue-link-code'
  inviteLink.target = '_blank'
  inviteLink.rel = 'noopener'
  const inviteCopyButton = el('button', { className: 'button-small', text: 'Скопировать' }) as HTMLButtonElement
  append(
    inviteResult,
    el('p', { text: 'Код:' }),
    inviteCode,
    el('p', { text: 'Истекает:' }),
    inviteExpires,
    inviteInstructions,
    inviteLink,
    inviteCopyButton
  )
  append(inviteCard, inviteTitle, inviteRow, inviteResult)

  const subscriptionCard = el('div', { className: 'card' })
  const subscriptionTitle = el('h3', { text: 'Subscription & Pricing' })
  const subscriptionForm = el('div', { className: 'venue-form-grid' })
  const trialEndInput = document.createElement('input')
  trialEndInput.className = 'venue-input'
  trialEndInput.type = 'date'
  const paidStartInput = document.createElement('input')
  paidStartInput.className = 'venue-input'
  paidStartInput.type = 'date'
  const basePriceInput = document.createElement('input')
  basePriceInput.className = 'venue-input'
  basePriceInput.type = 'number'
  basePriceInput.min = '0'
  basePriceInput.placeholder = 'Base price (minor units)'
  const overridePriceInput = document.createElement('input')
  overridePriceInput.className = 'venue-input'
  overridePriceInput.type = 'number'
  overridePriceInput.min = '0'
  overridePriceInput.placeholder = 'Override price (minor units)'
  const currencyLabel = el('span', { text: '' })
  const saveSubscriptionButton = el('button', { text: 'Сохранить настройки' }) as HTMLButtonElement
  append(
    subscriptionForm,
    trialEndInput,
    paidStartInput,
    basePriceInput,
    overridePriceInput,
    currencyLabel,
    saveSubscriptionButton
  )

  const scheduleTitle = el('h4', { text: 'Price schedule' })
  const scheduleList = el('div', { className: 'venue-menu-categories' })
  const scheduleActions = el('div', { className: 'venue-inline-actions' })
  const addScheduleButton = el('button', { text: 'Добавить строку' }) as HTMLButtonElement
  const saveScheduleButton = el('button', { text: 'Сохранить расписание' }) as HTMLButtonElement
  append(scheduleActions, addScheduleButton, saveScheduleButton)
  append(subscriptionCard, subscriptionTitle, subscriptionForm, scheduleTitle, scheduleList, scheduleActions)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, summaryCard, statusCard, ownersCard, inviteCard, subscriptionCard, status, error)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    summaryTitle,
    summaryInfo,
    ownersList,
    statusButtons,
    userSearch,
    userResults,
    selectedUserLabel,
    ownerRoleSelect,
    assignButton,
    inviteTtlInput,
    inviteButton,
    inviteResult,
    inviteCode,
    inviteExpires,
    inviteInstructions,
    inviteLink,
    inviteCopyButton,
    trialEndInput,
    paidStartInput,
    basePriceInput,
    overridePriceInput,
    currencyLabel,
    saveSubscriptionButton,
    scheduleList,
    addScheduleButton,
    saveScheduleButton
  }
}

function toDateInputValue(value: string | null | undefined) {
  if (!value) return ''
  return value.split('T')[0]
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString()
}

function formatOwnerName(owner: PlatformVenueDetailResponse['owners'][number]) {
  const name = [owner.firstName, owner.lastName].filter(Boolean).join(' ')
  if (name) return name
  if (owner.username) return owner.username
  return `User ${owner.userId}`
}

function renderScheduleRow(
  item: PlatformPriceScheduleItemDto,
  onRemove: () => void
): HTMLDivElement {
  const row = el('div', { className: 'venue-menu-item' })
  row.dataset.role = 'schedule-row'
  const info = el('div', { className: 'venue-menu-item-info' })
  const effectiveInput = document.createElement('input')
  effectiveInput.className = 'venue-input'
  effectiveInput.type = 'date'
  effectiveInput.value = toDateInputValue(item.effectiveFrom)
  effectiveInput.dataset.role = 'schedule-date'

  const priceInput = document.createElement('input')
  priceInput.className = 'venue-input'
  priceInput.type = 'number'
  priceInput.min = '1'
  priceInput.value = item.priceMinor ? String(item.priceMinor) : ''
  priceInput.dataset.role = 'schedule-price'

  const currency = el('p', { className: 'venue-order-sub', text: item.currency })
  append(info, effectiveInput, priceInput, currency)

  const actions = el('div', { className: 'venue-menu-item-actions' })
  const removeButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement
  removeButton.addEventListener('click', onRemove)
  append(actions, removeButton)

  append(row, info, actions)
  return row
}

export function renderPlatformVenueDetailScreen(options: PlatformVenueDetailOptions) {
  const { root, backendUrl, isDebug, venueId } = options
  if (!root) return () => undefined
  const refs = buildDetailDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let venueAbort: AbortController | null = null
  let subscriptionAbort: AbortController | null = null
  let searchAbort: AbortController | null = null
  let loadSeq = 0
  let selectedUser: PlatformUserDto | null = null
  let currentVenue: PlatformVenueDetailResponse | null = null
  let currentSubscription: PlatformSubscriptionSettingsResponse | null = null
  let currentInvite: PlatformOwnerInviteResponse | null = null
  let scheduleItems: PlatformPriceScheduleItemDto[] = []
  let isLoading = false
  let isAssigning = false
  let isUpdatingSettings = false
  let isUpdatingSchedule = false
  let isUpdatingStatus = false
  let isInviting = false

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
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
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) => (action.label === 'Повторить' ? { ...action, onClick: retry } : action))
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: retry }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const setLoadingState = (value: boolean) => {
    isLoading = value
    refs.assignButton.disabled = value || !selectedUser || isAssigning
    refs.inviteButton.disabled = value || isInviting
    refs.saveSubscriptionButton.disabled = value || isUpdatingSettings
    refs.saveScheduleButton.disabled = value || isUpdatingSchedule
    updateActionButtons()
  }

  const updateActionButtons = () => {
    refs.statusButtons.querySelectorAll('button').forEach((button) => {
      button.disabled = isLoading || isUpdatingStatus
    })
  }

  const renderSummary = () => {
    if (!currentVenue) return
    refs.summaryTitle.textContent = currentVenue.venue.name
    refs.summaryInfo.replaceChildren(
      el('p', { text: `#${currentVenue.venue.id}` }),
      el('p', { text: `Статус: ${currentVenue.venue.status}` }),
      el('p', { text: `Создано: ${formatDateTime(currentVenue.venue.createdAt)}` }),
      el('p', { text: `Город: ${currentVenue.venue.city ?? '—'}` }),
      el('p', { text: `Адрес: ${currentVenue.venue.address ?? '—'}` })
    )
    refs.ownersList.replaceChildren()
    if (!currentVenue.owners.length) {
      refs.ownersList.appendChild(el('p', { className: 'venue-empty', text: 'Owners не назначены.' }))
    } else {
      currentVenue.owners.forEach((owner) => {
        const row = el('div', { className: 'venue-staff-row' })
        const info = el('div', { className: 'venue-staff-info' })
        append(
          info,
          el('strong', { text: formatOwnerName(owner) }),
          el('p', { className: 'venue-order-sub', text: `User #${owner.userId} · ${owner.role}` })
        )
        append(row, info)
        refs.ownersList.appendChild(row)
      })
    }
  }

  const renderInvite = () => {
    if (!currentInvite) {
      refs.inviteResult.hidden = true
      return
    }
    refs.inviteCode.textContent = currentInvite.code
    refs.inviteExpires.textContent = formatDateTime(currentInvite.expiresAt)
    refs.inviteInstructions.textContent = currentInvite.instructions
    if (currentInvite.deepLink) {
      refs.inviteLink.textContent = currentInvite.deepLink
      refs.inviteLink.href = currentInvite.deepLink
      refs.inviteLink.hidden = false
    } else {
      refs.inviteLink.textContent = ''
      refs.inviteLink.href = '#'
      refs.inviteLink.hidden = true
    }
    refs.inviteResult.hidden = false
  }

  const renderSubscription = () => {
    if (!currentSubscription) return
    refs.trialEndInput.value = toDateInputValue(currentSubscription.settings.trialEndDate)
    refs.paidStartInput.value = toDateInputValue(currentSubscription.settings.paidStartDate)
    refs.basePriceInput.value = currentSubscription.settings.basePriceMinor
      ? String(currentSubscription.settings.basePriceMinor)
      : ''
    refs.overridePriceInput.value = currentSubscription.settings.priceOverrideMinor
      ? String(currentSubscription.settings.priceOverrideMinor)
      : ''
    refs.currencyLabel.textContent = `Currency: ${currentSubscription.settings.currency}`
    scheduleItems = currentSubscription.schedule.map((item) => ({ ...item }))
    renderSchedule()
  }

  const renderSchedule = () => {
    refs.scheduleList.replaceChildren()
    if (!scheduleItems.length) {
      refs.scheduleList.appendChild(el('p', { className: 'venue-empty', text: 'Расписание пустое.' }))
    }
    scheduleItems.forEach((item, index) => {
      refs.scheduleList.appendChild(
        renderScheduleRow(item, () => {
          scheduleItems = scheduleItems.filter((_, itemIndex) => itemIndex !== index)
          renderSchedule()
        })
      )
    })
  }

  const renderStatusButtons = () => {
    refs.statusButtons.replaceChildren()
    const actions: Array<{ action: string; label: string; confirm: boolean }> = [
      { action: 'publish', label: 'Publish', confirm: false },
      { action: 'hide', label: 'Hide', confirm: true },
      { action: 'pause', label: 'Pause', confirm: true },
      { action: 'suspend', label: 'Suspend', confirm: true },
      { action: 'archive', label: 'Archive', confirm: true },
      { action: 'delete', label: 'Delete', confirm: true }
    ]
    actions.forEach((item) => {
      const button = el('button', { className: 'button-small', text: item.label }) as HTMLButtonElement
      button.addEventListener('click', () => void handleStatusChange(item.action, item.confirm))
      refs.statusButtons.appendChild(button)
    })
  }

  const loadVenue = async () => {
    venueAbort?.abort()
    venueAbort = new AbortController()
    const seq = ++loadSeq
    setStatus('Загрузка заведения...')
    hideError()
    const result = await platformGetVenue(backendUrl, venueId, deps, venueAbort.signal)
    if (disposed || seq !== loadSeq) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      setStatus('')
      showError(result.error, loadVenue)
      return
    }
    currentVenue = result.data
    renderSummary()
    renderStatusButtons()
    updateActionButtons()
    setStatus('')
  }

  const loadSubscription = async () => {
    subscriptionAbort?.abort()
    subscriptionAbort = new AbortController()
    const result = await platformGetSubscription(backendUrl, venueId, deps, subscriptionAbort.signal)
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      showError(result.error, loadSubscription)
      return
    }
    currentSubscription = result.data
    renderSubscription()
  }

  const loadAll = async () => {
    setLoadingState(true)
    await Promise.all([loadVenue(), loadSubscription()])
    if (disposed) return
    setLoadingState(false)
  }

  const handleStatusChange = async (action: string, confirmAction: boolean) => {
    if (isUpdatingStatus) return
    if (confirmAction) {
      const ok = window.confirm(`Подтвердите действие: ${action}.`)
      if (!ok) return
    }
    isUpdatingStatus = true
    updateActionButtons()
    const result = await platformChangeVenueStatus(backendUrl, venueId, action, deps)
    isUpdatingStatus = false
    updateActionButtons()
    if (disposed) return
    if (!result.ok) {
      showError(result.error, () => void handleStatusChange(action, confirmAction))
      return
    }
    showToast('Статус обновлён')
    currentVenue = { ...currentVenue!, venue: result.data.venue }
    renderSummary()
  }

  const renderUserResults = (users: PlatformUserDto[], query: string) => {
    refs.userResults.replaceChildren()
    if (!query) return
    if (!users.length) {
      refs.userResults.appendChild(el('p', { className: 'venue-empty', text: 'Пользователи не найдены.' }))
      return
    }
    users.forEach((user) => {
      const row = el('div', { className: 'venue-staff-row' })
      const info = el('div', { className: 'venue-staff-info' })
      append(
        info,
        el('strong', { text: user.displayName }),
        el('p', { className: 'venue-order-sub', text: `User #${user.userId}` }),
        el('p', { className: 'venue-order-sub', text: user.username ? `@${user.username}` : '' })
      )
      const actions = el('div', { className: 'venue-staff-actions' })
      const selectButton = el('button', { className: 'button-small', text: 'Выбрать' }) as HTMLButtonElement
      selectButton.addEventListener('click', () => {
        selectedUser = user
        refs.selectedUserLabel.textContent = `Выбран: ${user.displayName} (#${user.userId})`
        refs.assignButton.disabled = isLoading || isAssigning
      })
      append(actions, selectButton)
      append(row, info, actions)
      refs.userResults.appendChild(row)
    })
  }

  const searchUsers = async (query: string) => {
    if (query.length < 2) {
      refs.userResults.replaceChildren()
      return
    }
    searchAbort?.abort()
    searchAbort = new AbortController()
    const result = await platformSearchUsers(backendUrl, query, deps, searchAbort.signal)
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      showError(result.error, () => void searchUsers(query))
      return
    }
    renderUserResults(result.data.users, query)
  }

  const handleAssignOwner = async () => {
    if (!selectedUser || isAssigning) return
    isAssigning = true
    refs.assignButton.disabled = true
    const result = await platformAssignOwner(
      backendUrl,
      venueId,
      {
        userId: selectedUser.userId,
        role: refs.ownerRoleSelect.value
      },
      deps
    )
    isAssigning = false
    refs.assignButton.disabled = isLoading || !selectedUser
    if (disposed) return
    if (!result.ok) {
      showError(result.error, handleAssignOwner)
      return
    }
    showToast(result.data.alreadyMember ? 'Owner уже назначен' : 'Owner назначен')
    await loadVenue()
  }

  const handleInvite = async () => {
    if (isInviting) return
    const ttlValue = refs.inviteTtlInput.value.trim()
    const ttl = ttlValue ? Number(ttlValue) : null
    if (ttlValue && (ttl === null || !Number.isFinite(ttl) || ttl < 60)) {
      showToast('TTL должен быть числом >= 60')
      return
    }
    isInviting = true
    refs.inviteButton.disabled = true
    const result = await platformCreateOwnerInvite(
      backendUrl,
      venueId,
      { ttlSeconds: ttl ?? null },
      deps
    )
    isInviting = false
    refs.inviteButton.disabled = isLoading
    if (disposed) return
    if (!result.ok) {
      showError(result.error, handleInvite)
      return
    }
    currentInvite = result.data
    renderInvite()
    showToast('Инвайт создан')
  }

  const handleCopyInvite = async () => {
    if (!currentInvite) return
    const payload = currentInvite.deepLink ?? currentInvite.code
    try {
      await navigator.clipboard.writeText(payload)
      showToast('Скопировано')
    } catch {
      showToast('Не удалось скопировать')
    }
  }

  const parsePrice = (value: string) => {
    if (!value.trim()) return null
    const parsed = Number(value)
    if (!Number.isFinite(parsed)) return null
    const rounded = Math.round(parsed)
    if (rounded <= 0) return null
    return rounded
  }

  const handleSubscriptionSave = async () => {
    if (isUpdatingSettings) return
    if (!currentSubscription) return
    const basePriceMinor = parsePrice(refs.basePriceInput.value)
    const priceOverrideMinor = parsePrice(refs.overridePriceInput.value)
    if (refs.basePriceInput.value.trim() && basePriceMinor === null) {
      showToast('Base price должен быть > 0')
      return
    }
    if (refs.overridePriceInput.value.trim() && priceOverrideMinor === null) {
      showToast('Override price должен быть > 0')
      return
    }
    isUpdatingSettings = true
    refs.saveSubscriptionButton.disabled = true
    const result = await platformUpdateSubscription(
      backendUrl,
      venueId,
      {
        trialEndDate: refs.trialEndInput.value || null,
        paidStartDate: refs.paidStartInput.value || null,
        basePriceMinor,
        priceOverrideMinor,
        currency: currentSubscription.settings.currency
      },
      deps
    )
    isUpdatingSettings = false
    refs.saveSubscriptionButton.disabled = isLoading
    if (disposed) return
    if (!result.ok) {
      showError(result.error, handleSubscriptionSave)
      return
    }
    currentSubscription = result.data
    renderSubscription()
    showToast('Настройки сохранены')
  }

  const handleScheduleSave = async () => {
    if (isUpdatingSchedule) return
    if (!currentSubscription) return
    const rows = Array.from(refs.scheduleList.querySelectorAll<HTMLDivElement>('[data-role="schedule-row"]'))
    const items: PlatformPriceScheduleItemDto[] = []
    for (const row of rows) {
      const dateInput = row.querySelector<HTMLInputElement>('[data-role="schedule-date"]')
      const priceInput = row.querySelector<HTMLInputElement>('[data-role="schedule-price"]')
      const effectiveFrom = dateInput?.value.trim() ?? ''
      const priceRaw = priceInput?.value.trim() ?? ''
      const price = parsePrice(priceRaw)
      if (!effectiveFrom) {
        showToast('Заполните даты в расписании')
        return
      }
      if (price === null) {
        showToast('Цена в расписании должна быть > 0')
        return
      }
      items.push({
        effectiveFrom,
        priceMinor: price,
        currency: currentSubscription.settings.currency
      })
    }
    isUpdatingSchedule = true
    refs.saveScheduleButton.disabled = true
    const result = await platformUpdatePriceSchedule(
      backendUrl,
      venueId,
      { items },
      deps
    )
    isUpdatingSchedule = false
    refs.saveScheduleButton.disabled = isLoading
    if (disposed) return
    if (!result.ok) {
      showError(result.error, handleScheduleSave)
      return
    }
    scheduleItems = result.data.items.map((item) => ({ ...item }))
    renderSchedule()
    showToast('Расписание сохранено')
  }

  const handleAddScheduleRow = () => {
    if (!currentSubscription) return
    scheduleItems = [
      ...scheduleItems,
      {
        effectiveFrom: '',
        priceMinor: 0,
        currency: currentSubscription.settings.currency
      }
    ]
    renderSchedule()
  }

  const disposeSearch = on(refs.userSearch, 'input', () => {
    const query = refs.userSearch.value.trim()
    void searchUsers(query)
  })

  const disposeAssign = on(refs.assignButton, 'click', () => void handleAssignOwner())
  const disposeInvite = on(refs.inviteButton, 'click', () => void handleInvite())
  const disposeCopyInvite = on(refs.inviteCopyButton, 'click', () => void handleCopyInvite())
  const disposeSaveSubscription = on(refs.saveSubscriptionButton, 'click', () => void handleSubscriptionSave())
  const disposeAddSchedule = on(refs.addScheduleButton, 'click', () => void handleAddScheduleRow())
  const disposeSaveSchedule = on(refs.saveScheduleButton, 'click', () => void handleScheduleSave())

  void loadAll()

  return () => {
    disposed = true
    venueAbort?.abort()
    subscriptionAbort?.abort()
    searchAbort?.abort()
    venueAbort = null
    subscriptionAbort = null
    searchAbort = null
    disposeSearch()
    disposeAssign()
    disposeInvite()
    disposeCopyInvite()
    disposeSaveSubscription()
    disposeAddSchedule()
    disposeSaveSchedule()
  }
}
