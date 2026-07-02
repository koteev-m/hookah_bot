import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  platformAssignOwner,
  platformChangeVenueStatus,
  platformCreateOwnerInvite,
  platformEnsureBillingCheckout,
  platformGetBilling,
  platformGetSubscription,
  platformGetVenue,
  platformMarkInvoicePaid,
  platformRevokeOwner,
  platformSearchUsers,
  platformUpdatePriceSchedule,
  platformUpdateSubscription
} from '../shared/api/platformApi'
import type { OwnerBillingInvoiceDto, OwnerBillingOverviewResponse } from '../shared/api/billingDtos'
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
  billingSummary: HTMLDivElement
  billingRefreshButton: HTMLButtonElement
  billingEnsureCheckoutButton: HTMLButtonElement
  billingOpenCheckoutButton: HTMLButtonElement
  billingInvoices: HTMLDivElement
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
  const ownersTitle = el('h3', { text: 'Владельцы' })
  const ownersList = el('div', { className: 'venue-staff-list' })
  append(summaryCard, summaryTitle, summaryInfo, ownersTitle, ownersList)

  const statusCard = el('div', { className: 'card' })
  const statusTitle = el('h3', { text: 'Статус' })
  const statusButtons = el('div', { className: 'venue-inline-actions' })
  append(statusCard, statusTitle, statusButtons)

  const ownersCard = el('div', { className: 'card' })
  const ownersHeader = el('h3', { text: 'Назначить владельца' })
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

  const assignButton = el('button', { text: 'Назначить владельца' }) as HTMLButtonElement
  const assignRow = el('div', { className: 'venue-inline-actions' })
  append(assignRow, ownerRoleSelect, assignButton)
  append(ownersCard, ownersHeader, searchRow, selectedUserLabel, userResults, assignRow)

  const inviteCard = el('div', { className: 'card' })
  const inviteTitle = el('h3', { text: 'Инвайт владельца' })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteTtlInput = document.createElement('input')
  inviteTtlInput.className = 'venue-input'
  inviteTtlInput.type = 'number'
  inviteTtlInput.min = '60'
  inviteTtlInput.placeholder = 'TTL (сек), минимум 60'
  const inviteButton = el('button', { text: 'Сгенерировать инвайт' }) as HTMLButtonElement
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
  const subscriptionTitle = el('h3', { text: 'Подписка и цены' })
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
  basePriceInput.min = '1'
  basePriceInput.placeholder = 'Базовая цена, копейки'
  const overridePriceInput = document.createElement('input')
  overridePriceInput.className = 'venue-input'
  overridePriceInput.type = 'number'
  overridePriceInput.min = '1'
  overridePriceInput.placeholder = 'Индивидуальная цена, копейки'
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

  const scheduleTitle = el('h4', { text: 'Расписание цен' })
  const scheduleList = el('div', { className: 'venue-menu-categories' })
  const scheduleActions = el('div', { className: 'venue-inline-actions' })
  const addScheduleButton = el('button', { text: 'Добавить строку' }) as HTMLButtonElement
  const saveScheduleButton = el('button', { text: 'Сохранить расписание' }) as HTMLButtonElement
  append(scheduleActions, addScheduleButton, saveScheduleButton)
  append(subscriptionCard, subscriptionTitle, subscriptionForm, scheduleTitle, scheduleList, scheduleActions)

  const billingCard = el('div', { className: 'card' })
  const billingTitle = el('h3', { text: 'Счета и оплата' })
  const billingSummary = el('div', { className: 'venue-summary' }) as HTMLDivElement
  const billingActions = el('div', { className: 'venue-inline-actions' })
  const billingRefreshButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Обновить'
  }) as HTMLButtonElement
  const billingEnsureCheckoutButton = el('button', {
    className: 'button-small',
    text: 'Создать/обновить ссылку'
  }) as HTMLButtonElement
  const billingOpenCheckoutButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Открыть оплату'
  }) as HTMLButtonElement
  const billingInvoices = el('div', { className: 'venue-staff-list' }) as HTMLDivElement
  append(billingActions, billingRefreshButton, billingEnsureCheckoutButton, billingOpenCheckoutButton)
  append(billingCard, billingTitle, billingSummary, billingActions, billingInvoices)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, summaryCard, statusCard, ownersCard, inviteCard, subscriptionCard, billingCard, status, error)
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
    saveScheduleButton,
    billingSummary,
    billingRefreshButton,
    billingEnsureCheckoutButton,
    billingOpenCheckoutButton,
    billingInvoices
  }
}

function toDateInputValue(value: string | null | undefined) {
  if (!value) return ''
  return value.split('T')[0]
}

function formatDateTime(value: string) {
  return new Date(value).toLocaleString()
}

function formatDate(value?: string | null) {
  if (!value) return '—'
  return value.split('T')[0]
}

function formatMoney(amountMinor?: number | null, currency?: string | null) {
  if (amountMinor === null || amountMinor === undefined) return '—'
  return `${(amountMinor / 100).toLocaleString('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })} ${currency ?? 'RUB'}`
}

function billingStatusLabel(status?: string | null) {
  switch ((status ?? '').toLowerCase()) {
    case 'trial':
      return 'Пробный период'
    case 'active':
      return 'Активна'
    case 'past_due':
      return 'Просрочена'
    case 'canceled':
      return 'Отменена'
    case 'suspended':
      return 'Приостановлена'
    case 'suspended_by_platform':
      return 'Заблокирована платформой'
    case 'unknown':
      return 'Не настроена'
    default:
      return status ?? '—'
  }
}

function invoiceStatusLabel(status: string) {
  switch (status.toUpperCase()) {
    case 'OPEN':
      return 'Открыт'
    case 'PAST_DUE':
      return 'Просрочен'
    case 'PAID':
      return 'Оплачен'
    case 'VOID':
      return 'Аннулирован'
    case 'DRAFT':
      return 'Черновик'
    default:
      return status
  }
}

function paymentReasonLabel(reason?: string | null) {
  switch (reason) {
    case 'provider_not_configured':
      return 'Провайдер оплаты не настроен.'
    case 'external_checkout_unavailable':
      return 'Внешняя ссылка оплаты пока недоступна.'
    case 'missing_price':
      return 'Не задана цена подписки.'
    case 'missing_billing_period':
      return 'Платёжный период ещё не определён.'
    case 'fake_provider_manual_only':
      return 'Тестовый провайдер: оплата только вручную.'
    case 'already_paid':
      return 'Текущий период уже оплачен.'
    default:
      return reason ? `Оплата недоступна: ${reason}` : 'Оплата недоступна.'
  }
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
  let billingAbort: AbortController | null = null
  let searchAbort: AbortController | null = null
  let loadSeq = 0
  let searchDebounce: ReturnType<typeof setTimeout> | null = null
  let selectedUser: PlatformUserDto | null = null
  let currentVenue: PlatformVenueDetailResponse | null = null
  let currentSubscription: PlatformSubscriptionSettingsResponse | null = null
  let currentBilling: OwnerBillingOverviewResponse | null = null
  let currentInvite: PlatformOwnerInviteResponse | null = null
  let scheduleItems: PlatformPriceScheduleItemDto[] = []
  let isLoading = false
  let isAssigning = false
  let isUpdatingSettings = false
  let isUpdatingSchedule = false
  let isUpdatingStatus = false
  let isEnsuringCheckout = false
  let isMarkingInvoicePaid = false
  let isInviting = false
  let isRevokingOwner = false

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
    refs.billingRefreshButton.disabled = value
    refs.billingEnsureCheckoutButton.disabled = value || isEnsuringCheckout
    refs.billingOpenCheckoutButton.disabled = value || !currentBilling?.checkoutUrl
    updateActionButtons()
  }

  const updateActionButtons = () => {
    refs.statusButtons.querySelectorAll('button').forEach((button) => {
      button.disabled = isLoading || isUpdatingStatus
    })
    const canRevokeOwner = (currentVenue?.owners.length ?? 0) > 1
    refs.ownersList.querySelectorAll<HTMLButtonElement>('[data-role="owner-revoke"]').forEach((button) => {
      button.disabled = isLoading || isRevokingOwner || !canRevokeOwner
    })
    refs.billingInvoices.querySelectorAll<HTMLButtonElement>('[data-role="mark-paid"]').forEach((button) => {
      button.disabled = isLoading || isMarkingInvoicePaid
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
      refs.ownersList.appendChild(el('p', { className: 'venue-empty', text: 'Владельцы не назначены.' }))
    } else {
      const canRevokeOwner = currentVenue.owners.length > 1
      currentVenue.owners.forEach((owner) => {
        const row = el('div', { className: 'venue-staff-row' })
        const info = el('div', { className: 'venue-staff-info' })
        append(
          info,
          el('strong', { text: formatOwnerName(owner) }),
          el('p', { className: 'venue-order-sub', text: `User #${owner.userId} · ${owner.role}` })
        )
        const actions = el('div', { className: 'venue-staff-actions' })
        const revokeButton = el('button', {
          className: 'button-small button-secondary',
          text: 'Отозвать'
        }) as HTMLButtonElement
        revokeButton.dataset.role = 'owner-revoke'
        revokeButton.disabled = isLoading || isRevokingOwner || !canRevokeOwner
        revokeButton.title = canRevokeOwner
          ? 'Отозвать OWNER доступ'
          : 'Нельзя отозвать последнего владельца'
        revokeButton.addEventListener('click', () => void handleRevokeOwner(owner))
        append(actions, revokeButton)
        append(row, info, actions)
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
    refs.currencyLabel.textContent = `Валюта: ${currentSubscription.settings.currency}`
    scheduleItems = currentSubscription.schedule.map((item) => ({ ...item }))
    renderSchedule()
  }

  const renderBilling = () => {
    refs.billingSummary.replaceChildren()
    refs.billingInvoices.replaceChildren()
    refs.billingOpenCheckoutButton.disabled = isLoading || !currentBilling?.checkoutUrl
    refs.billingEnsureCheckoutButton.disabled = isLoading || isEnsuringCheckout
    if (!currentBilling) {
      refs.billingSummary.appendChild(el('p', { className: 'venue-empty', text: 'Биллинг не загружен.' }))
      refs.billingInvoices.appendChild(el('p', { className: 'venue-empty', text: 'Счета не загружены.' }))
      return
    }

    refs.billingSummary.replaceChildren(
      el('p', { text: `Статус: ${billingStatusLabel(currentBilling.subscriptionStatus)}` }),
      el('p', { text: `Цена: ${formatMoney(currentBilling.priceMinor, currentBilling.currency)}` }),
      el('p', { text: `Trial до: ${formatDate(currentBilling.settingsTrialEndDate ?? currentBilling.trialEndAt)}` }),
      el('p', { text: `Платный период с: ${formatDate(currentBilling.settingsPaidStartDate ?? currentBilling.paidStartAt)}` }),
      el('p', { text: `Оплачено до: ${formatDate(currentBilling.paidThrough)}` }),
      el('p', {
        text: currentBilling.paymentAvailable
          ? 'Внешняя оплата доступна.'
          : paymentReasonLabel(currentBilling.unavailableReason)
      })
    )

    if (!currentBilling.invoices.length) {
      refs.billingInvoices.appendChild(el('p', { className: 'venue-empty', text: 'Счетов пока нет.' }))
      return
    }

    currentBilling.invoices.forEach((invoice) => {
      refs.billingInvoices.appendChild(renderBillingInvoiceRow(invoice))
    })
    updateActionButtons()
  }

  const renderBillingInvoiceRow = (invoice: OwnerBillingInvoiceDto) => {
    const row = el('div', { className: 'venue-staff-row' })
    const info = el('div', { className: 'venue-staff-info' })
    append(
      info,
      el('strong', { text: `Счёт #${invoice.id} · ${invoiceStatusLabel(invoice.status)}` }),
      el('p', {
        className: 'venue-order-sub',
        text: `${formatDate(invoice.periodStart)} — ${formatDate(invoice.periodEnd)} · ${formatMoney(
          invoice.amountMinor,
          invoice.currency
        )}`
      }),
      el('p', { className: 'venue-order-sub', text: `Срок оплаты: ${formatDate(invoice.dueAt)}` }),
      el('p', {
        className: 'venue-order-sub',
        text: invoice.paidAt ? `Оплачен: ${formatDate(invoice.paidAt)}` : ''
      })
    )
    const actions = el('div', { className: 'venue-staff-actions' })
    if (invoice.checkoutUrl) {
      const openButton = el('button', {
        className: 'button-small button-secondary',
        text: 'Открыть оплату'
      }) as HTMLButtonElement
      openButton.addEventListener('click', () => window.open(invoice.checkoutUrl ?? '', '_blank', 'noopener'))
      append(actions, openButton)
    }
    const payable = invoice.status === 'OPEN' || invoice.status === 'PAST_DUE'
    if (payable) {
      const markPaidButton = el('button', {
        className: 'button-small',
        text: 'Отметить оплачено'
      }) as HTMLButtonElement
      markPaidButton.dataset.role = 'mark-paid'
      markPaidButton.addEventListener('click', () => void handleMarkInvoicePaid(invoice))
      append(actions, markPaidButton)
    }
    append(row, info, actions)
    return row
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
    const status = currentVenue?.venue.status.toUpperCase()
    if (!status || status === 'DELETED') {
      refs.statusButtons.appendChild(
        el('p', {
          className: 'venue-empty',
          text: status === 'DELETED' ? 'Заведение удалено. Действия недоступны.' : 'Статус не загружен.'
        })
      )
      return
    }
    const actions: Array<{ action: string; label: string; confirm: boolean }> = [
      ...(status === 'DRAFT' ? [{ action: 'publish', label: 'Опубликовать', confirm: false }] : []),
      ...(status === 'HIDDEN' ? [{ action: 'publish', label: 'Опубликовать', confirm: false }] : []),
      ...(status === 'PAUSED' ? [{ action: 'publish', label: 'Вернуть в работу', confirm: true }] : []),
      ...(status === 'SUSPENDED' ? [{ action: 'publish', label: 'Разблокировать и опубликовать', confirm: true }] : []),
      ...(status === 'ARCHIVED' ? [{ action: 'publish', label: 'Восстановить — сразу опубликует', confirm: true }] : []),
      ...(status === 'PUBLISHED' ? [{ action: 'hide', label: 'Скрыть', confirm: true }] : []),
      ...(status === 'PUBLISHED' ? [{ action: 'pause', label: 'Приостановить', confirm: true }] : []),
      ...(status !== 'SUSPENDED' && status !== 'ARCHIVED'
        ? [{ action: 'suspend', label: 'Заблокировать', confirm: true }]
        : []),
      ...(status !== 'ARCHIVED' ? [{ action: 'archive', label: 'Архивировать', confirm: true }] : []),
      { action: 'delete', label: 'Удалить', confirm: true }
    ]
    actions.forEach((item) => {
      const button = el('button', { className: 'button-small', text: item.label }) as HTMLButtonElement
      button.addEventListener('click', () => void handleStatusChange(item.action, item.confirm, item.label))
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

  const loadBilling = async () => {
    billingAbort?.abort()
    billingAbort = new AbortController()
    const result = await platformGetBilling(backendUrl, venueId, deps, billingAbort.signal)
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      showError(result.error, loadBilling)
      return
    }
    currentBilling = result.data
    renderBilling()
  }

  const loadAll = async () => {
    setLoadingState(true)
    await Promise.all([loadVenue(), loadSubscription(), loadBilling()])
    if (disposed) return
    setLoadingState(false)
  }

  const handleStatusChange = async (action: string, confirmAction: boolean, label: string) => {
    if (isUpdatingStatus) return
    if (confirmAction) {
      const ok = window.confirm(`Подтвердите действие: ${label}.`)
      if (!ok) return
    }
    isUpdatingStatus = true
    updateActionButtons()
    const result = await platformChangeVenueStatus(backendUrl, venueId, action, deps)
    isUpdatingStatus = false
    updateActionButtons()
    if (disposed) return
    if (!result.ok) {
      showError(result.error, () => void handleStatusChange(action, confirmAction, label))
      return
    }
    showToast('Статус обновлён')
    currentVenue = { ...currentVenue!, venue: result.data.venue }
    renderSummary()
    renderStatusButtons()
    updateActionButtons()
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

  const clearSearchDebounce = () => {
    if (searchDebounce) {
      clearTimeout(searchDebounce)
      searchDebounce = null
    }
  }

  const searchUsers = async (query: string) => {
    if (query.length < 2) {
      searchAbort?.abort()
      searchAbort = null
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
    showToast(result.data.alreadyMember ? 'Владелец уже назначен' : 'Владелец назначен')
    await loadVenue()
  }

  const handleRevokeOwner = async (owner: PlatformVenueDetailResponse['owners'][number]) => {
    if (isRevokingOwner) return
    if ((currentVenue?.owners.length ?? 0) <= 1) {
      showToast('Нельзя отозвать последнего владельца')
      return
    }
    const ok = window.confirm(`Отозвать OWNER доступ у ${formatOwnerName(owner)} (#${owner.userId})?`)
    if (!ok) return
    isRevokingOwner = true
    updateActionButtons()
    hideError()
    const result = await platformRevokeOwner(backendUrl, venueId, owner.userId, deps)
    isRevokingOwner = false
    updateActionButtons()
    if (disposed) return
    if (!result.ok) {
      showError(result.error, () => void handleRevokeOwner(owner))
      return
    }
    showToast('Владелец отозван')
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
    try {
      await navigator.clipboard.writeText(currentInvite.copyText)
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
      showToast('Базовая цена должна быть больше 0')
      return
    }
    if (refs.overridePriceInput.value.trim() && priceOverrideMinor === null) {
      showToast('Индивидуальная цена должна быть больше 0')
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
    const sortedItems = [...items].sort((a, b) => a.effectiveFrom.localeCompare(b.effectiveFrom))
    for (let i = 1; i < sortedItems.length; i += 1) {
      if (sortedItems[i].effectiveFrom === sortedItems[i - 1].effectiveFrom) {
        showToast('Даты в расписании должны быть уникальными')
        return
      }
    }
    scheduleItems = sortedItems.map((item) => ({ ...item }))
    renderSchedule()
    isUpdatingSchedule = true
    refs.saveScheduleButton.disabled = true
    const result = await platformUpdatePriceSchedule(
      backendUrl,
      venueId,
      { items: sortedItems },
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

  const handleEnsureCheckout = async () => {
    if (isEnsuringCheckout) return
    isEnsuringCheckout = true
    refs.billingEnsureCheckoutButton.disabled = true
    hideError()
    const result = await platformEnsureBillingCheckout(backendUrl, venueId, deps)
    isEnsuringCheckout = false
    if (disposed) return
    if (!result.ok) {
      refs.billingEnsureCheckoutButton.disabled = isLoading
      showError(result.error, handleEnsureCheckout)
      return
    }
    currentBilling = result.data
    renderBilling()
    showToast(result.data.paymentAvailable ? 'Ссылка оплаты готова' : paymentReasonLabel(result.data.unavailableReason))
  }

  const handleOpenCheckout = () => {
    const url = currentBilling?.checkoutUrl
    if (!url) {
      showToast(paymentReasonLabel(currentBilling?.unavailableReason))
      return
    }
    window.open(url, '_blank', 'noopener')
  }

  const handleMarkInvoicePaid = async (invoice: OwnerBillingInvoiceDto) => {
    if (isMarkingInvoicePaid) return
    const ok = window.confirm(
      `Отметить счёт #${invoice.id} оплаченным вручную? Сумма: ${formatMoney(invoice.amountMinor, invoice.currency)}.`
    )
    if (!ok) return
    isMarkingInvoicePaid = true
    updateActionButtons()
    hideError()
    const result = await platformMarkInvoicePaid(backendUrl, invoice.id, deps)
    isMarkingInvoicePaid = false
    updateActionButtons()
    if (disposed) return
    if (!result.ok) {
      showError(result.error, () => void handleMarkInvoicePaid(invoice))
      return
    }
    showToast(result.data.alreadyPaid ? 'Счёт уже был оплачен' : 'Счёт отмечен оплаченным')
    await Promise.all([loadBilling(), loadSubscription()])
  }

  const disposeSearch = on(refs.userSearch, 'input', () => {
    const query = refs.userSearch.value.trim()
    clearSearchDebounce()
    if (searchAbort) {
      searchAbort.abort()
      searchAbort = null
    }
    if (query.length < 2) {
      void searchUsers(query)
      return
    }
    searchDebounce = setTimeout(() => {
      if (disposed) return
      void searchUsers(query)
    }, 300)
  })

  const disposeAssign = on(refs.assignButton, 'click', () => void handleAssignOwner())
  const disposeInvite = on(refs.inviteButton, 'click', () => void handleInvite())
  const disposeCopyInvite = on(refs.inviteCopyButton, 'click', () => void handleCopyInvite())
  const disposeSaveSubscription = on(refs.saveSubscriptionButton, 'click', () => void handleSubscriptionSave())
  const disposeAddSchedule = on(refs.addScheduleButton, 'click', () => void handleAddScheduleRow())
  const disposeSaveSchedule = on(refs.saveScheduleButton, 'click', () => void handleScheduleSave())
  const disposeBillingRefresh = on(refs.billingRefreshButton, 'click', () => void loadBilling())
  const disposeBillingEnsureCheckout = on(refs.billingEnsureCheckoutButton, 'click', () => void handleEnsureCheckout())
  const disposeBillingOpenCheckout = on(refs.billingOpenCheckoutButton, 'click', () => handleOpenCheckout())

  void loadAll()

  return () => {
    disposed = true
    venueAbort?.abort()
    subscriptionAbort?.abort()
    billingAbort?.abort()
    searchAbort?.abort()
    venueAbort = null
    subscriptionAbort = null
    billingAbort = null
    searchAbort = null
    clearSearchDebounce()
    disposeSearch()
    disposeAssign()
    disposeInvite()
    disposeCopyInvite()
    disposeSaveSubscription()
    disposeAddSchedule()
    disposeSaveSchedule()
    disposeBillingRefresh()
    disposeBillingEnsureCheckout()
    disposeBillingOpenCheckout()
  }
}
