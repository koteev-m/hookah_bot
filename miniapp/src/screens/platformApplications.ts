import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import {
  platformCreateAndLinkOnboardingRequest,
  platformDecideOnboardingRequest,
  platformGetOnboardingRequest,
  platformListOnboardingRequests,
  platformUpdateOnboardingTerms
} from '../shared/api/platformApi'
import type {
  PlatformOnboardingRequestDto,
  PlatformOnboardingTermsRequest
} from '../shared/api/platformDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError } from '../shared/ui/apiErrorPresenter'
import { showToast } from '../shared/ui/toast'

type CommonOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigate: (hash: string) => void
}

export type PlatformApplicationsListOptions = CommonOptions

export type PlatformApplicationDetailOptions = CommonOptions & {
  requestId: number
}

function deps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function workspaceNav(onNavigate: (hash: string) => void) {
  const nav = el('div', { className: 'venue-inline-actions ownership-tabs' })
  const requests = el('button', { className: 'button-small', text: 'Заявки' }) as HTMLButtonElement
  const venues = el('button', { className: 'button-small button-secondary', text: 'Кальянные' }) as HTMLButtonElement
  const owners = el('button', { className: 'button-small button-secondary', text: 'Владельцы' }) as HTMLButtonElement
  requests.addEventListener('click', () => onNavigate('#/applications'))
  venues.addEventListener('click', () => onNavigate('#/venues'))
  owners.addEventListener('click', () => onNavigate('#/owners'))
  append(nav, requests, venues, owners)
  return nav
}

function applicantName(request: PlatformOnboardingRequestDto) {
  const fullName = [request.applicant.firstName?.trim(), request.applicant.lastName?.trim()].filter(Boolean).join(' ')
  if (fullName) return fullName
  if (request.applicant.username?.trim()) return `@${request.applicant.username.replace(/^@/, '')}`
  return `User #${request.applicant.userId}`
}

function statusCopy(request: PlatformOnboardingRequestDto) {
  if (request.status === 'PENDING') return 'На рассмотрении'
  if (request.status === 'REJECTED') return 'Отклонена'
  if (request.status === 'CANCELLED') return 'Отменена'
  return request.linkedVenueId ? `Одобрена · venue #${request.linkedVenueId}` : 'Одобрена · ожидает создания'
}

function commercialTermsReady(request: PlatformOnboardingRequestDto) {
  if (!request.trialConfigured || request.currentPriceRub == null || request.currentPriceRub < 0) return false
  const hasFuturePrice = request.futurePriceRub != null
  const hasFutureDate = Boolean(request.futurePriceEffectiveOn)
  if (hasFuturePrice !== hasFutureDate) return false
  return request.futurePriceRub == null || request.futurePriceRub > 0
}

function labeledControl<T extends HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(
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

function configureLiveRegions(status: HTMLParagraphElement, error: HTMLDivElement) {
  status.setAttribute('role', 'status')
  status.setAttribute('aria-live', 'polite')
  status.setAttribute('aria-atomic', 'true')
  status.tabIndex = -1
  error.setAttribute('role', 'alert')
  error.setAttribute('aria-live', 'assertive')
  error.setAttribute('aria-atomic', 'true')
  error.tabIndex = -1
}

function showError(
  container: HTMLDivElement,
  error: ApiErrorInfo,
  isDebug: boolean,
  retry?: () => void
) {
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
  container.hidden = false
  container.dataset.severity = presentation.severity
  container.replaceChildren(
    el('h3', { text: presentation.title }),
    el('p', { text: presentation.message }),
    actions
  )
  container.focus({ preventScroll: true })
}

export function renderPlatformApplicationsListScreen(options: PlatformApplicationsListOptions) {
  const { root, backendUrl, isDebug, onNavigate } = options
  if (!root) return () => undefined
  const wrapper = el('div', { className: 'venue-orders ownership-workspace' })
  const header = el('section', { className: 'card' })
  const statusFilter = document.createElement('select')
  statusFilter.className = 'venue-select'
  ;[
    ['any', 'Все статусы'],
    ['PENDING', 'На рассмотрении'],
    ['APPROVED', 'Одобрено'],
    ['REJECTED', 'Отклонено'],
    ['CANCELLED', 'Отменено']
  ].forEach(([value, label]) => statusFilter.appendChild(new Option(label, value)))
  const search = document.createElement('input')
  search.type = 'search'
  search.className = 'venue-input'
  search.placeholder = 'Название, город, username или request id'
  const filters = el('div', { className: 'venue-form-grid' })
  append(
    filters,
    labeledControl('platform-applications-status', 'Статус заявки', statusFilter),
    labeledControl('platform-applications-search', 'Поиск заявок', search)
  )
  append(
    header,
    el('h2', { text: 'Подключение и ownership' }),
    el('p', { text: 'Решения выполняются явно. Создание DRAFT и назначение OWNER происходят только после одобрения и коммерческих условий.' }),
    workspaceNav(onNavigate),
    filters
  )
  const status = el('p', { className: 'status' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  configureLiveRegions(status, error)
  const list = el('div', { className: 'venue-orders-list' }) as HTMLDivElement
  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  const requestDeps = deps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let seq = 0
  let debounce: ReturnType<typeof setTimeout> | null = null

  const load = async () => {
    controller?.abort()
    const active = new AbortController()
    controller = active
    const current = ++seq
    error.hidden = true
    status.textContent = 'Загрузка...'
    const result = await platformListOnboardingRequests(
      backendUrl,
      {
        status: statusFilter.value === 'any' ? null : statusFilter.value,
        q: search.value.trim() || null,
        limit: 100,
        offset: 0
      },
      requestDeps,
      active.signal
    )
    if (disposed || current !== seq) return
    controller = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      status.textContent = ''
      showError(error, result.error, isDebug, () => void load())
      return
    }
    status.textContent = `Найдено: ${result.data.requests.length}`
    list.replaceChildren()
    if (!result.data.requests.length) {
      list.appendChild(el('p', { className: 'venue-empty', text: 'Заявки не найдены.' }))
      return
    }
    result.data.requests.forEach((request) => {
      const row = el('div', { className: 'venue-order-row' })
      row.dataset.applicationStatus = request.status
      const meta = el('div', { className: 'venue-order-meta' })
      append(
        meta,
        el('strong', { text: request.venueName }),
        el('p', { className: 'venue-order-sub', text: `#${request.id} · ${request.city} · ${applicantName(request)}` }),
        el('p', { className: 'ownership-status', text: statusCopy(request) })
      )
      const open = el('button', { className: 'button-small', text: 'Открыть' }) as HTMLButtonElement
      open.addEventListener('click', () => onNavigate(`#/application/${request.id}`))
      append(row, meta, open)
      list.appendChild(row)
    })
  }

  const scheduleLoad = () => {
    if (debounce) clearTimeout(debounce)
    debounce = setTimeout(() => void load(), 250)
  }
  const disposables = [
    on(statusFilter, 'change', () => void load()),
    on(search, 'input', scheduleLoad)
  ]
  void load()
  return () => {
    disposed = true
    controller?.abort()
    if (debounce) clearTimeout(debounce)
    disposables.forEach((dispose) => dispose())
  }
}

export function renderPlatformApplicationDetailScreen(options: PlatformApplicationDetailOptions) {
  const { root, backendUrl, isDebug, onNavigate, requestId } = options
  if (!root) return () => undefined
  const wrapper = el('div', { className: 'venue-orders ownership-workspace' })
  const header = el('section', { className: 'card' })
  const back = el('button', { className: 'button-small button-secondary', text: '← К заявкам' }) as HTMLButtonElement
  back.addEventListener('click', () => onNavigate('#/applications'))
  append(header, back, el('h2', { text: `Заявка #${requestId}` }), workspaceNav(onNavigate))
  const status = el('p', { className: 'status' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  configureLiveRegions(status, error)
  const content = el('div') as HTMLDivElement
  append(wrapper, header, status, error, content)
  root.replaceChildren(wrapper)

  const requestDeps = deps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let seq = 0
  let mutationPending = false
  let mutationControls: Array<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement> = []

  const setMutationPending = (pending: boolean) => {
    mutationPending = pending
    mutationControls.forEach((control) => {
      control.disabled = pending
    })
  }

  const beginMutation = () => {
    if (mutationPending) return false
    error.hidden = true
    setMutationPending(true)
    return true
  }

  const mutate = async (
    action: 'approve' | 'reject' | 'close',
    confirmation?: string
  ) => {
    if (mutationPending) return
    if (confirmation && !window.confirm(confirmation)) return
    if (!beginMutation()) return
    status.textContent = 'Сохраняем...'
    const result = await platformDecideOnboardingRequest(backendUrl, requestId, action, requestDeps)
    if (disposed) return
    if (!result.ok) {
      status.textContent = ''
      setMutationPending(false)
      showError(error, result.error, isDebug, () => void mutate(action, confirmation))
      return
    }
    const announcement =
      action === 'approve'
        ? `Заявка #${result.data.request.id} одобрена.`
        : action === 'reject'
          ? `Заявка #${result.data.request.id} отклонена.`
          : `Заявка #${result.data.request.id} закрыта.`
    showToast(announcement)
    await load(announcement)
    if (!disposed) setMutationPending(false)
  }

  const saveTerms = async (form: HTMLFormElement) => {
    if (mutationPending) return
    if (!form.reportValidity()) return
    const data = new FormData(form)
    const futurePriceRaw = String(data.get('futurePriceRub') ?? '').trim()
    const body: PlatformOnboardingTermsRequest = {
      trialConfigured: true,
      trialEndsOn: String(data.get('trialEndsOn') ?? '').trim() || null,
      currentPriceRub: Number(data.get('currentPriceRub')),
      futurePriceRub: futurePriceRaw ? Number(futurePriceRaw) : null,
      futurePriceEffectiveOn: String(data.get('futurePriceEffectiveOn') ?? '').trim() || null,
      commercialNote: String(data.get('commercialNote') ?? '').trim() || null
    }
    if (!beginMutation()) return
    status.textContent = 'Сохраняем условия...'
    const result = await platformUpdateOnboardingTerms(backendUrl, requestId, body, requestDeps)
    if (disposed) return
    if (!result.ok) {
      status.textContent = ''
      setMutationPending(false)
      showError(error, result.error, isDebug, () => void saveTerms(form))
      return
    }
    const announcement = `Коммерческие условия заявки #${result.data.request.id} сохранены.`
    showToast(announcement)
    await load(announcement)
    if (!disposed) setMutationPending(false)
  }

  const createAndLink = async (request: PlatformOnboardingRequestDto) => {
    if (mutationPending) return
    const confirmed = window.confirm(
      `Создать DRAFT «${request.venueName}», назначить действующего OWNER и связать заявку? Новое заведение не будет выбрано автоматически.`
    )
    if (!confirmed) return
    if (!beginMutation()) return
    status.textContent = 'Создаём и связываем...'
    const result = await platformCreateAndLinkOnboardingRequest(backendUrl, requestId, requestDeps)
    if (disposed) return
    if (!result.ok) {
      status.textContent = ''
      setMutationPending(false)
      showError(error, result.error, isDebug, () => void createAndLink(request))
      return
    }
    showToast(result.data.created ? 'Черновик создан и связан.' : 'Заявка уже связана с существующим результатом.')
    onNavigate(`#/venue/${result.data.venueId}`)
  }

  const render = (request: PlatformOnboardingRequestDto) => {
    content.replaceChildren()
    const nextMutationControls: Array<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement> = []
    const details = el('section', { className: 'card' })
    append(
      details,
      el('h3', { text: request.venueName }),
      el('p', { className: 'ownership-status', text: statusCopy(request) }),
      el('p', { text: `Applicant: ${applicantName(request)} (#${request.applicant.userId})` }),
      el('p', { text: `Город: ${request.city}` }),
      el('p', { text: `Контакт: ${request.contact}` }),
      el('p', { text: `Комментарий: ${request.comment?.trim() || 'нет'}` })
    )
    const actions = el('div', { className: 'venue-inline-actions' })
    if (request.status === 'PENDING') {
      const approve = el('button', { text: 'Одобрить' }) as HTMLButtonElement
      const reject = el('button', { className: 'button-danger', text: 'Отклонить' }) as HTMLButtonElement
      approve.addEventListener('click', () => void mutate('approve'))
      reject.addEventListener('click', () =>
        void mutate('reject', 'Отклонить заявку? После этого applicant сможет отправить новую заявку.')
      )
      nextMutationControls.push(approve, reject)
      append(actions, approve, reject)
    }
    if (request.status === 'APPROVED' && !request.linkedVenueId) {
      const close = el('button', { className: 'button-danger', text: 'Закрыть без создания' }) as HTMLButtonElement
      close.addEventListener('click', () =>
        void mutate('close', 'Закрыть одобренную заявку без создания заведения? Это действие отменит заявку.')
      )
      nextMutationControls.push(close)
      actions.appendChild(close)
    }
    details.appendChild(actions)
    content.appendChild(details)

    if (request.status === 'APPROVED' && !request.linkedVenueId) {
      const form = document.createElement('form')
      form.className = 'card ownership-terms-form'
      const grid = el('div', { className: 'venue-form-grid' })
      const trialEnd = document.createElement('input')
      trialEnd.type = 'date'
      trialEnd.name = 'trialEndsOn'
      trialEnd.className = 'venue-input'
      trialEnd.value = request.trialEndsOn ?? ''
      const currentPrice = document.createElement('input')
      currentPrice.type = 'number'
      currentPrice.name = 'currentPriceRub'
      currentPrice.className = 'venue-input'
      currentPrice.min = '0'
      currentPrice.required = true
      currentPrice.placeholder = 'Текущая цена, ₽'
      currentPrice.value = request.currentPriceRub == null ? '' : String(request.currentPriceRub)
      const futurePrice = document.createElement('input')
      futurePrice.type = 'number'
      futurePrice.name = 'futurePriceRub'
      futurePrice.className = 'venue-input'
      futurePrice.min = '1'
      futurePrice.placeholder = 'Будущая цена, ₽ (необязательно)'
      futurePrice.value = request.futurePriceRub == null ? '' : String(request.futurePriceRub)
      const futureDate = document.createElement('input')
      futureDate.type = 'date'
      futureDate.name = 'futurePriceEffectiveOn'
      futureDate.className = 'venue-input'
      futureDate.value = request.futurePriceEffectiveOn ?? ''
      const note = document.createElement('textarea')
      note.name = 'commercialNote'
      note.className = 'venue-input'
      note.maxLength = 1000
      note.placeholder = 'Коммерческая заметка (необязательно)'
      note.value = request.commercialNote ?? ''
      const save = el('button', { text: 'Сохранить условия' }) as HTMLButtonElement
      save.type = 'submit'
      append(
        grid,
        labeledControl('platform-onboarding-trial-end', 'Пробный период до (необязательно)', trialEnd),
        labeledControl('platform-onboarding-current-price', 'Текущая цена, ₽', currentPrice),
        labeledControl('platform-onboarding-future-price', 'Будущая цена, ₽ (необязательно)', futurePrice),
        labeledControl(
          'platform-onboarding-future-price-date',
          'Дата начала будущей цены (необязательно)',
          futureDate
        ),
        labeledControl('platform-onboarding-commercial-note', 'Коммерческая заметка (необязательно)', note)
      )
      append(form, el('h3', { text: 'Коммерческие условия' }), grid, save)
      form.addEventListener('submit', (event) => {
        event.preventDefault()
        void saveTerms(form)
      })
      nextMutationControls.push(trialEnd, currentPrice, futurePrice, futureDate, note, save)
      content.appendChild(form)

      const createCard = el('section', { className: 'card' })
      const create = el('button', { text: 'Создать DRAFT и связать' }) as HTMLButtonElement
      const termsReady = commercialTermsReady(request)
      create.disabled = !termsReady
      if (termsReady) {
        create.addEventListener('click', () => void createAndLink(request))
        nextMutationControls.push(create)
      }
      append(
        createCard,
        el('h3', { text: 'Создание заведения' }),
        el('p', {
          text:
            (termsReady ? '' : 'Сначала сохраните корректные коммерческие условия. ') +
            'На этой границе применяется текущий commercial quota. При недостаточном лимите DRAFT, membership и link не создаются.'
        }),
        create
      )
      content.appendChild(createCard)
    }
    mutationControls = nextMutationControls
    setMutationPending(mutationPending)
  }

  const load = async (announcement?: string) => {
    controller?.abort()
    const active = new AbortController()
    controller = active
    const current = ++seq
    error.hidden = true
    status.textContent = 'Загрузка...'
    const result = await platformGetOnboardingRequest(backendUrl, requestId, requestDeps, active.signal)
    if (disposed || current !== seq) return
    controller = null
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      status.textContent = ''
      showError(error, result.error, isDebug, () => void load())
      return
    }
    status.textContent = announcement ?? ''
    render(result.data.request)
    if (announcement) status.focus({ preventScroll: true })
  }

  void load()
  return () => {
    disposed = true
    controller?.abort()
  }
}
