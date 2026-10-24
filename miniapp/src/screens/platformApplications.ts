import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
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
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError } from '../shared/ui/apiErrorPresenter'
import { showToast } from '../shared/ui/toast'
import { getTelegramContext } from '../shared/telegram'
import { telegramUsernameUrl } from '../shared/validation/telegramUsername'

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
  return 'Заявитель без имени'
}

function statusCopy(request: PlatformOnboardingRequestDto) {
  if (request.status === 'PENDING') return 'На рассмотрении'
  if (request.status === 'REJECTED') return 'Отклонена'
  if (request.status === 'CANCELLED') return 'Отменена'
  return request.linkedVenueId ? 'Кабинет создан' : 'Одобрена · ожидает создания'
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
  const presentation = presentApiError(error, { isDebug, scope: 'generic' })
  if (normalizeErrorCode(error) === ApiErrorCodes.INVALID_INPUT) {
    const message = error.message ?? ''
    presentation.message = message.startsWith('Лимит заведений исчерпан:')
      ? 'Лимит кальянных владельца исчерпан. Сначала увеличьте лимит, затем повторите создание кабинета.'
      : message.includes('активной роли Owner')
        ? 'У заявителя больше нет роли владельца. Сначала восстановите его доступ, затем повторите действие.'
        : 'Проверьте статус заявки и условия: стоимость подписки, пробный период, сумму и дату будущей цены. Исправьте данные или обновите заявку и повторите действие.'
  }
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
  search.placeholder = 'Название, город, имя в Telegram или номер заявки'
  const filters = el('div', { className: 'venue-form-grid' })
  append(
    filters,
    labeledControl('platform-applications-status', 'Статус заявки', statusFilter),
    labeledControl('platform-applications-search', 'Поиск заявок', search)
  )
  append(
    header,
    el('h2', { text: 'Заявки на подключение' }),
    el('p', { text: 'Рассмотрите заявку, сохраните условия подписки и создайте кабинет кальянной.' }),
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
  const wrapper = el('div', { className: 'venue-orders ownership-workspace application-detail' })
  const header = el('div', { className: 'application-nav' })
  const back = el('button', { className: 'button-small button-secondary', text: '← К заявкам' }) as HTMLButtonElement
  back.addEventListener('click', () => onNavigate('#/applications'))
  append(header, back, el('h2', { text: `Заявка #${requestId}` }))
  const status = el('p', { className: 'status' })
  const error = el('div', { className: 'error-card' }) as HTMLDivElement
  error.hidden = true
  configureLiveRegions(status, error)
  const content = el('div', { className: 'application-content' }) as HTMLDivElement
  append(wrapper, header, status, error, content)
  root.replaceChildren(wrapper)

  const requestDeps = deps(isDebug)
  let disposed = false
  let controller: AbortController | null = null
  let seq = 0
  let mutationPending = false
  let detailFresh = false
  let refreshAvailability: () => void = () => undefined
  let canCreateFromCurrent = () => false
  let mutationControls: Array<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement> = []

  const setMutationPending = (pending: boolean) => {
    mutationPending = pending
    mutationControls.forEach((control) => {
      control.disabled = pending
    })
    refreshAvailability()
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
      trialEndsOn: data.has('useTrial') ? String(data.get('trialEndsOn') ?? '').trim() || null : null,
      currentPriceRub: Number(data.get('currentPriceRub')),
      futurePriceRub: data.has('useFuturePrice') && futurePriceRaw ? Number(futurePriceRaw) : null,
      futurePriceEffectiveOn: data.has('useFuturePrice') ? String(data.get('futurePriceEffectiveOn') ?? '').trim() || null : null,
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
    if (mutationPending || !canCreateFromCurrent()) return
    const confirmed = window.confirm(
      `Создать кабинет кальянной «${request.venueName}»? Заявитель получит роль владельца. Кальянная останется неопубликованной и не будет выбрана автоматически.`
    )
    if (!confirmed) return
    if (!beginMutation()) return
    status.textContent = 'Создаём кабинет кальянной...'
    const result = await platformCreateAndLinkOnboardingRequest(backendUrl, requestId, requestDeps)
    if (disposed) return
    if (!result.ok) {
      status.textContent = ''
      setMutationPending(false)
      showError(error, result.error, isDebug, () => void createAndLink(request))
      return
    }
    showToast(result.data.created ? 'Кабинет кальянной создан.' : 'Заявка уже связана с существующим результатом.')
    onNavigate(`#/venue/${result.data.venueId}`)
  }

  const render = (request: PlatformOnboardingRequestDto) => {
    content.replaceChildren()
    refreshAvailability = () => undefined
    canCreateFromCurrent = () => false
    const nextMutationControls: Array<HTMLInputElement | HTMLTextAreaElement | HTMLButtonElement> = []
    const details = el('section', { className: 'card application-summary' })
    details.dataset.applicationStatus = request.status
    append(
      details,
      el('h3', { text: request.venueName }),
      el('p', { className: 'application-city', text: request.city }),
      el('p', { className: 'ownership-status', text: statusCopy(request) }),
      el('p', { text: `Заявитель: ${applicantName(request)}` }),
      el('p', { text: `Контакт: ${request.contact}` }),
      el('p', { className: 'application-comment', text: `Комментарий: ${request.comment?.trim() || 'нет'}` })
    )
    const contactActions = el('div', { className: 'venue-inline-actions' })
    const telegramUrl = telegramUsernameUrl(request.applicant.username) ?? telegramUsernameUrl(request.contact)
    if (telegramUrl) {
      const telegram = el('a', { className: 'application-telegram', text: 'Написать в Telegram' })
      telegram.href = telegramUrl
      telegram.target = '_blank'
      telegram.rel = 'noopener noreferrer'
      telegram.addEventListener('click', (event) => {
        const webApp = getTelegramContext().webApp
        if (!webApp?.openTelegramLink) return
        event.preventDefault()
        try {
          webApp.openTelegramLink(telegramUrl)
        } catch {
          showToast('Не удалось открыть Telegram. Скопируйте контакт и найдите заявителя вручную.')
        }
      })
      contactActions.appendChild(telegram)
    }
    if (request.contact.trim()) {
      const copy = el('button', { className: 'button-small button-secondary', text: 'Копировать контакт' })
      copy.addEventListener('click', async () => {
        try {
          await navigator.clipboard.writeText(request.contact)
          if (!disposed) showToast('Контакт скопирован.')
        } catch {
          if (!disposed) showToast('Не удалось скопировать контакт. Выделите и скопируйте его вручную.')
        }
      })
      contactActions.appendChild(copy)
    }
    details.appendChild(contactActions)
    const actions = el('div', { className: 'venue-inline-actions' })
    if (request.status === 'PENDING') {
      const approve = el('button', { text: 'Одобрить' }) as HTMLButtonElement
      const reject = el('button', { className: 'button-danger', text: 'Отклонить' }) as HTMLButtonElement
      approve.addEventListener('click', () => void mutate('approve'))
      reject.addEventListener('click', () =>
        void mutate('reject', 'Отклонить заявку? После этого заявитель сможет отправить новую заявку.')
      )
      nextMutationControls.push(approve, reject)
      append(actions, approve, reject)
    }
    details.appendChild(actions)
    content.appendChild(details)

    if (request.status === 'APPROVED' && !request.linkedVenueId) {
      const form = document.createElement('form')
      form.className = 'card ownership-terms-form'
      const termsReady = commercialTermsReady(request)
      const termsState = el('p', { className: 'application-hint' })
      termsState.setAttribute('role', 'status')
      termsState.setAttribute('aria-live', 'polite')
      const priceChoice = el('fieldset', { className: 'application-price-choice' })
      priceChoice.appendChild(el('legend', { text: 'Стоимость подписки' }))
      const free = document.createElement('input')
      free.type = 'radio'
      free.name = 'priceMode'
      free.value = 'free'
      free.required = true
      free.checked = request.currentPriceRub === 0
      const paid = document.createElement('input')
      paid.type = 'radio'
      paid.name = 'priceMode'
      paid.value = 'paid'
      paid.required = true
      paid.checked = request.currentPriceRub != null && request.currentPriceRub > 0
      const choice = (control: HTMLInputElement, text: string) => {
        const label = el('label', { className: 'application-choice' })
        append(label, control, el('span', { text }))
        return label
      }
      append(priceChoice, choice(free, 'Бесплатно · 0 ₽ в месяц'), choice(paid, 'Платная подписка'))
      const currentPrice = document.createElement('input')
      currentPrice.type = 'number'
      currentPrice.inputMode = 'numeric'
      currentPrice.name = 'currentPriceRub'
      currentPrice.className = 'venue-input'
      currentPrice.min = '1'
      currentPrice.max = '21474836'
      currentPrice.step = '1'
      currentPrice.value = request.currentPriceRub == null ? '' : String(request.currentPriceRub)
      const priceField = labeledControl('platform-onboarding-current-price', 'Стоимость в месяц, ₽', currentPrice)

      const trialToggle = document.createElement('input')
      trialToggle.type = 'checkbox'
      trialToggle.name = 'useTrial'
      trialToggle.checked = Boolean(request.trialEndsOn)
      const trialEnd = document.createElement('input')
      trialEnd.type = 'date'
      trialEnd.name = 'trialEndsOn'
      trialEnd.className = 'venue-input'
      trialEnd.value = request.trialEndsOn ?? ''
      const trialField = labeledControl('platform-onboarding-trial-end', 'Пробный период до', trialEnd)

      const futureToggle = document.createElement('input')
      futureToggle.type = 'checkbox'
      futureToggle.name = 'useFuturePrice'
      futureToggle.checked = request.futurePriceRub != null || Boolean(request.futurePriceEffectiveOn)
      const futurePrice = document.createElement('input')
      futurePrice.type = 'number'
      futurePrice.inputMode = 'numeric'
      futurePrice.name = 'futurePriceRub'
      futurePrice.className = 'venue-input'
      futurePrice.min = '1'
      futurePrice.max = currentPrice.max
      futurePrice.step = '1'
      futurePrice.value = request.futurePriceRub == null ? '' : String(request.futurePriceRub)
      const futureDate = document.createElement('input')
      futureDate.type = 'date'
      futureDate.name = 'futurePriceEffectiveOn'
      futureDate.className = 'venue-input'
      futureDate.value = request.futurePriceEffectiveOn ?? ''
      const futureFields = el('div', { className: 'venue-form-grid' })
      append(
        futureFields,
        el('p', { className: 'application-hint', text: 'Укажите сумму больше 0 ₽ и дату вместе. Это новая ежемесячная стоимость подписки.' }),
        labeledControl('platform-onboarding-future-price', 'Будущая стоимость в месяц, ₽', futurePrice),
        labeledControl('platform-onboarding-future-price-date', 'Новая стоимость с', futureDate)
      )
      const noteDetails = el('details', { className: 'application-extra' })
      noteDetails.open = Boolean(request.commercialNote)
      const note = document.createElement('textarea')
      note.name = 'commercialNote'
      note.className = 'venue-input'
      note.maxLength = 1000
      note.value = request.commercialNote ?? ''
      append(noteDetails, el('summary', { text: 'Заметка об условиях' }), labeledControl('platform-onboarding-commercial-note', 'Коммерческая заметка (необязательно)', note))
      const save = el('button', { text: 'Сохранить условия' }) as HTMLButtonElement
      save.type = 'submit'
      append(
        form,
        el('h3', { text: '1. Условия подключения' }),
        termsState,
        el('p', { className: 'application-hint', text: 'Это ежемесячная стоимость подписки кальянной на платформу в рублях.' }),
        priceChoice,
        priceField,
        choice(trialToggle, 'Добавить пробный период'),
        trialField,
        choice(futureToggle, 'Запланировать изменение цены'),
        futureFields,
        noteDetails,
        save
      )
      form.addEventListener('submit', (event) => {
        event.preventDefault()
        void saveTerms(form)
      })
      nextMutationControls.push(free, paid, trialToggle, futureToggle, trialEnd, currentPrice, futurePrice, futureDate, note, save)
      content.appendChild(form)

      const createCard = el('section', { className: 'card' })
      const create = el('button', { text: 'Создать кабинет кальянной' }) as HTMLButtonElement
      const createHint = el('p', { className: 'application-hint', id: 'application-create-hint' })
      create.setAttribute('aria-describedby', createHint.id)
      let lastPaidPrice = paid.checked ? currentPrice.value : ''
      const updateFields = () => {
        priceField.hidden = !paid.checked
        currentPrice.type = paid.checked ? 'number' : 'hidden'
        currentPrice.required = paid.checked
        currentPrice.value = free.checked ? '0' : paid.checked ? lastPaidPrice : ''
        trialField.hidden = !trialToggle.checked
        trialEnd.required = trialToggle.checked
        futureFields.hidden = !futureToggle.checked
        futurePrice.required = futureToggle.checked
        futureDate.required = futureToggle.checked
        // Hidden optional fields must not take part in native form validation.
        trialEnd.disabled = mutationPending || !trialToggle.checked
        futurePrice.disabled = mutationPending || !futureToggle.checked
        futureDate.disabled = mutationPending || !futureToggle.checked
      }
      const values = () => JSON.stringify({
        mode: free.checked ? 'free' : paid.checked ? 'paid' : '',
        price: currentPrice.value,
        trialEnabled: trialToggle.checked,
        trial: trialToggle.checked ? trialEnd.value : '',
        futureEnabled: futureToggle.checked,
        futurePrice: futureToggle.checked ? futurePrice.value : '',
        futureDate: futureToggle.checked ? futureDate.value : '',
        note: note.value.trim()
      })
      updateFields()
      const savedValues = values()
      refreshAvailability = () => {
        updateFields()
        const dirty = values() !== savedValues
        create.disabled = mutationPending || !detailFresh || !termsReady || dirty
        save.disabled = mutationPending || !detailFresh
        termsState.textContent = mutationPending
          ? 'Сохраняем изменения…'
          : dirty
            ? 'Есть несохранённые изменения.'
            : termsReady ? 'Условия сохранены.' : 'Условия ещё не сохранены.'
        createHint.textContent = !detailFresh
          ? 'Дождитесь загрузки актуальной заявки. При ошибке повторите загрузку.'
          : dirty
            ? 'Сначала сохраните изменения условий.'
            : !termsReady
              ? 'Выберите бесплатное подключение или платную подписку и сохраните условия.'
              : 'Условия сохранены. Можно создать кабинет кальянной.'
      }
      form.addEventListener('input', () => {
        if (paid.checked && currentPrice.type === 'number') lastPaidPrice = currentPrice.value
        refreshAvailability()
      })
      canCreateFromCurrent = () => !create.disabled
      create.addEventListener('click', () => {
        if (!create.disabled) void createAndLink(request)
      })
      nextMutationControls.push(create)
      append(
        createCard,
        el('h3', { text: '2. Кабинет кальянной' }),
        el('p', { className: 'application-hint', text: 'Заявитель получит роль владельца. Кальянная останется неопубликованной: её нужно будет настроить и опубликовать отдельно.' }),
        createHint,
        create,
        el('p', { className: 'application-hint', text: 'При создании проверяется лимит кальянных владельца. Если лимит исчерпан, кабинет не создастся — сначала потребуется увеличить лимит.' })
      )
      content.appendChild(createCard)
      const extra = el('details', { className: 'application-extra' })
      const close = el('button', { className: 'button-secondary', text: 'Закрыть без создания' }) as HTMLButtonElement
      close.addEventListener('click', () =>
        void mutate('close', 'Закрыть одобренную заявку без создания заведения? Это действие отменит заявку.')
      )
      nextMutationControls.push(close)
      append(extra, el('summary', { text: 'Дополнительные действия' }), el('p', { className: 'application-hint', text: 'Закрытие отменит заявку. Кабинет кальянной не будет создан.' }), close)
      content.appendChild(extra)
    } else {
      const guidance = request.linkedVenueId
        ? 'Кабинет уже создан. Откройте его для настройки кальянной.'
        : request.status === 'PENDING'
          ? 'Сначала рассмотрите заявку. Условия подключения и создание кабинета доступны после одобрения.'
          : 'Заявка завершена. Создание кабинета по ней недоступно; заявитель может подать новую заявку.'
      details.appendChild(el('p', { className: 'application-hint', text: guidance }))
      if (request.linkedVenueId) {
        const open = el('button', { text: 'Открыть кабинет кальянной' })
        open.addEventListener('click', () => onNavigate(`#/venue/${request.linkedVenueId}`))
        details.appendChild(open)
      }
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
    detailFresh = false
    refreshAvailability()
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
    detailFresh = true
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
