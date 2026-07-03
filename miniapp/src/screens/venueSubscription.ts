import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import type { OwnerBillingInvoiceDto, OwnerBillingOverviewResponse } from '../shared/api/billingDtos'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { venueEnsureSubscriptionCheckout, venueGetSubscription } from '../shared/api/venueApi'
import type { VenueAccessDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatBillingDate, formatNextBillingDate } from '../shared/ui/billingDates'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

type VenueSubscriptionOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type VenueSubscriptionRefs = {
  status: HTMLParagraphElement
  summary: HTMLDivElement
  invoices: HTMLDivElement
  refreshButton: HTMLButtonElement
  ensureCheckoutButton: HTMLButtonElement
  openCheckoutButton: HTMLButtonElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function formatMoney(amountMinor?: number | null, currency?: string | null) {
  if (amountMinor === null || amountMinor === undefined) return '—'
  return formatPrice(amountMinor, currency ?? 'RUB')
}

function subscriptionStatusLabel(status?: string | null) {
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
      return 'Онлайн-оплата не подключена. Оплату ведёт платформа вручную.'
    case 'external_checkout_unavailable':
      return 'Ссылка оплаты пока недоступна. Обратитесь к платформе или оплатите вручную.'
    case 'missing_price':
      return 'Цена подписки ещё не задана. Обратитесь к платформе.'
    case 'missing_billing_period':
      return 'Платный период ещё не задан. Обратитесь к платформе.'
    case 'fake_provider_manual_only':
      return 'Онлайн-оплата не подключена. Оплату ведёт платформа вручную.'
    case 'already_paid':
      return 'Текущий период уже оплачен.'
    case 'advance_window_not_open':
      return 'Следующий период ещё не в окне оплаты.'
    default:
      return reason ? `Оплата недоступна: ${reason}` : 'Оплата недоступна.'
  }
}

function formatNextPaymentDate(overview: OwnerBillingOverviewResponse) {
  if (overview.nextPaymentDate) return formatBillingDate(overview.nextPaymentDate)
  return formatNextBillingDate(overview.paidThrough)
}

function safeCheckoutUrl(url?: string | null) {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'https:' || parsed.protocol === 'http:' ? url : null
  } catch {
    return null
  }
}

function subscriptionNextStep(overview: OwnerBillingOverviewResponse) {
  if (overview.paidThrough) return `Оплата учтена. Следующая оплата с ${formatNextPaymentDate(overview)}.`
  if (!overview.priceMinor) return paymentReasonLabel('missing_price')
  if (!(overview.settingsPaidStartDate ?? overview.paidStartAt)) return paymentReasonLabel('missing_billing_period')
  if (!overview.paymentAvailable) return paymentReasonLabel(overview.unavailableReason)
  return 'Можно перейти к оплате картой.'
}

function paidThroughRows(overview: OwnerBillingOverviewResponse) {
  if (!overview.paidThrough) {
    return [el('p', { text: 'Оплачено до: —' })]
  }
  return [
    el('p', { text: `Оплачено до ${formatBillingDate(overview.paidThrough)} включительно` }),
    el('p', { text: `Следующая оплата с ${formatNextPaymentDate(overview)}` })
  ]
}

function buildDom(root: HTMLDivElement): VenueSubscriptionRefs {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const title = el('h2', { text: 'Подписка' })
  const status = el('p', { className: 'status', text: '' })
  append(header, title)

  const summaryCard = el('section', { className: 'card' })
  const summaryTitle = el('h3', { text: 'Состояние' })
  const summary = el('div', { className: 'venue-summary' }) as HTMLDivElement
  const actions = el('div', { className: 'venue-inline-actions' })
  const refreshButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Проверить оплату'
  }) as HTMLButtonElement
  const ensureCheckoutButton = el('button', {
    className: 'button-small',
    text: 'Подготовить оплату'
  }) as HTMLButtonElement
  const openCheckoutButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Оплатить картой'
  }) as HTMLButtonElement
  append(actions, refreshButton, ensureCheckoutButton, openCheckoutButton)
  append(summaryCard, summaryTitle, summary, actions)

  const invoicesCard = el('section', { className: 'card' })
  const invoicesTitle = el('h3', { text: 'Счета' })
  const invoices = el('div', { className: 'venue-staff-list' }) as HTMLDivElement
  append(invoicesCard, invoicesTitle, invoices)

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  append(wrapper, header, status, error, summaryCard, invoicesCard)
  root.replaceChildren(wrapper)

  return {
    status,
    summary,
    invoices,
    refreshButton,
    ensureCheckoutButton,
    openCheckoutButton,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails
  }
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

export function renderVenueSubscriptionScreen(options: VenueSubscriptionOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let current: OwnerBillingOverviewResponse | null = null
  let inFlight = false
  let ensuring = false

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

  const updateButtons = () => {
    refs.refreshButton.disabled = inFlight
    refs.ensureCheckoutButton.disabled = inFlight || ensuring
    refs.openCheckoutButton.disabled = inFlight || !safeCheckoutUrl(current?.checkoutUrl)
  }

  const renderInvoice = (invoice: OwnerBillingInvoiceDto) => {
    const row = el('div', { className: 'venue-staff-row' })
    const info = el('div', { className: 'venue-staff-info' })
    append(
      info,
      el('strong', { text: `Счёт #${invoice.id} · ${invoiceStatusLabel(invoice.status)}` }),
      el('p', {
        className: 'venue-order-sub',
        text: `Период: ${formatBillingDate(invoice.periodStart)} — ${formatBillingDate(
          invoice.periodEnd
        )} включительно · ${formatMoney(
          invoice.amountMinor,
          invoice.currency
        )}`
      }),
      el('p', { className: 'venue-order-sub', text: `Срок оплаты: ${formatBillingDate(invoice.dueAt)}` }),
      el('p', {
        className: 'venue-order-sub',
        text: invoice.paidAt ? `Оплачен: ${formatBillingDate(invoice.paidAt)}` : ''
      })
    )
    append(row, info)
    return row
  }

  const render = () => {
    refs.summary.replaceChildren()
    refs.invoices.replaceChildren()
    if (!current) {
      refs.summary.appendChild(el('p', { className: 'venue-empty', text: 'Подписка не загружена.' }))
      refs.invoices.appendChild(el('p', { className: 'venue-empty', text: 'Счета не загружены.' }))
      updateButtons()
      return
    }
    refs.summary.replaceChildren(
      el('p', { text: `Статус: ${subscriptionStatusLabel(current.subscriptionStatus)}` }),
      el('p', { text: `Цена: ${formatMoney(current.priceMinor, current.currency)}` }),
      el('p', { text: `Пробный период до: ${formatBillingDate(current.settingsTrialEndDate ?? current.trialEndAt)}` }),
      el('p', { text: `Платный период с: ${formatBillingDate(current.settingsPaidStartDate ?? current.paidStartAt)}` }),
      ...paidThroughRows(current),
      el('p', { text: subscriptionNextStep(current) })
    )
    if (!current.invoices.length) {
      refs.invoices.appendChild(el('p', { className: 'venue-empty', text: 'Счетов пока нет.' }))
    } else {
      current.invoices.forEach((invoice) => refs.invoices.appendChild(renderInvoice(invoice)))
    }
    updateButtons()
  }

  const load = async () => {
    if (inFlight) return
    if (access.role !== 'OWNER') {
      setStatus('Раздел доступен только владельцу заведения.')
      refs.summary.replaceChildren(el('p', { className: 'venue-empty', text: 'Недостаточно прав.' }))
      refs.invoices.replaceChildren()
      updateButtons()
      return
    }
    inFlight = true
    updateButtons()
    hideError()
    setStatus('Загрузка...')
    loadAbort?.abort()
    const controller = new AbortController()
    loadAbort = controller
    const result = await venueGetSubscription(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadAbort !== controller) return
    inFlight = false
    loadAbort = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      setStatus('')
      showError(result.error, load)
      updateButtons()
      return
    }
    current = result.data
    setStatus('')
    render()
  }

  const ensureCheckout = async () => {
    if (ensuring || access.role !== 'OWNER') return
    ensuring = true
    updateButtons()
    hideError()
    const result = await venueEnsureSubscriptionCheckout(backendUrl, venueId, deps)
    ensuring = false
    if (disposed) return
    if (!result.ok) {
      showError(result.error, ensureCheckout)
      updateButtons()
      return
    }
    current = result.data
    render()
    showToast(result.data.paymentAvailable ? 'Оплата готова' : paymentReasonLabel(result.data.unavailableReason))
  }

  const openCheckout = () => {
    const url = safeCheckoutUrl(current?.checkoutUrl)
    if (!url) {
      showToast(paymentReasonLabel(current?.unavailableReason))
      return
    }
    window.open(url, '_blank', 'noopener')
  }

  const disposables = [
    on(refs.refreshButton, 'click', () => void load()),
    on(refs.ensureCheckoutButton, 'click', () => void ensureCheckout()),
    on(refs.openCheckoutButton, 'click', () => openCheckout())
  ]

  void load()

  return () => {
    disposed = true
    loadAbort?.abort()
    loadAbort = null
    disposables.forEach((dispose) => dispose())
  }
}
