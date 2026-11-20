import { clearSession, getAccessToken as getSessionAccessToken, getStoredSession } from '../shared/api/auth'
import { botVisitExitHint, checkGuestVisit } from '../shared/state/tableContext'
import { requestApi } from '../shared/api/request'
import type { ApiErrorInfo } from '../shared/api/types'
import { isVenueAccessForUser, venueGetMe } from '../shared/api/venueApi'
import type { VenueConnectionApplicationDto, VenueConnectionApplicationWriteResponse } from '../shared/api/venueDtos'
import { openHomeMode } from '../shared/homeMode'
import { getTelegramContext } from '../shared/telegram'
import { append, el } from '../shared/ui/dom'
import { venueApplicationFields } from '../shared/ui/venueApplicationFields'

const endpoint = '/api/guest/venue-applications'
const statusText = {
  PENDING: 'На рассмотрении',
  APPROVED: 'Одобрена. Платформа готовит кабинет.',
  REJECTED: 'Отклонена',
  CANCELLED: 'Отменена'
}

type SubmissionAttempt = {
  values: string[]
  knownIds: number[]
}

function applicationDraftKey(actor: number) {
  return `hookah_guest_venue_application:user:${actor}`
}

function failure(error: ApiErrorInfo, fallback: string) {
  if (error.status === 401) return 'Сессия истекла. Откройте приложение из Telegram ещё раз.'
  if (error.status === 403) return 'Нет доступа к заявкам. Проверьте учётную запись Telegram.'
  if (error.status === 429) return 'Слишком много запросов. Подождите несколько минут и попробуйте снова.'
  return fallback
}

export function renderGuestVenueApplication(root: HTMLElement, backendUrl: string, isDebug: boolean, onBack: () => void) {
  const controller = new AbortController()
  const { signal } = controller
  const deps = {
    clearSession,
    isDebug,
    async getAccessToken() {
      const result = await getSessionAccessToken()
      if (result.ok && !signal.aborted) persistDraft()
      return result
    }
  }
  const back = el('button', { className: 'button-small button-secondary', text: 'Назад' })
  back.addEventListener('click', onBack)
  const header = el('section', { className: 'card' })
  append(header, back, el('h1', { text: 'Для заведений' }),
    el('p', { text: 'Расскажите о своей кальянной. Доступ к кабинету появится после рассмотрения заявки и создания заведения платформой.' }))
  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  status.tabIndex = -1
  const receipt = el('p', { className: 'status' })
  receipt.setAttribute('role', 'status')
  receipt.hidden = true
  const current = el('section', { className: 'guest-application-current venue-orders-list' })
  const history = el('details', { className: 'guest-application-history' })
  const historySummary = el('summary')
  const historyList = el('div', { className: 'venue-orders-list' })
  append(history, historySummary, historyList)
  const refresh = el('button', { className: 'button-secondary button-small', text: 'Обновить статус' })
  refresh.hidden = true
  header.appendChild(refresh)
  const form = el('form', { className: 'card ownership-application-form' })
  const fields = venueApplicationFields('guest-application', 'Дополнительная информация (необязательно)')
  const submit = el('button', { text: 'Отправить заявку' })
  submit.type = 'submit'
  const startNew = el('button', { className: 'button-secondary', text: 'Начать отдельную заявку' })
  startNew.type = 'button'
  startNew.hidden = true
  const discard = el('button', { className: 'button-secondary', text: 'Очистить черновик' })
  discard.type = 'button'
  discard.hidden = true
  append(form, el('h2', { text: 'Заявка на подключение' }), fields.formGrid, submit, startNew, discard)
  const inputs = [fields.venueName, fields.city, fields.contact, fields.comment]
  let formActor = getTelegramContext().telegramUserId
  let draftKey: string | null = null
  let unsavedDraft: string[] | null = null
  let attempt: SubmissionAttempt | null = null
  let canStartNew = false

  function showReceipt(message: string) {
    receipt.textContent = message
    receipt.hidden = !message
  }

  function forgetAttempt() {
    if (draftKey) {
      try { sessionStorage.removeItem(draftKey + ':attempt') } catch { /* Recheck on reload. */ }
    }
    attempt = null
    canStartNew = false
  }

  function confirmAttempt() {
    const submitted = attempt?.values
    if (submitted && inputs.every((input, i) => input.value === submitted[i])) resetDraft()
    forgetAttempt()
  }

  function reconcileAttempt(rows: VenueConnectionApplicationDto[]) {
    if (!attempt) return
    const { values, knownIds } = attempt
    const submitted = values.map(value => value.trim())
    const previousIds = new Set(knownIds)
    const matches = rows.filter(row => !previousIds.has(row.id) &&
      [row.venueName, row.city, row.contact, row.comment ?? ''].every((value, i) => value === submitted[i]))
    if (matches.length === 1) {
      confirmAttempt()
      showReceipt(`Заявка №${matches[0].id} уже принята.`)
      return
    }
    canStartNew = true
    showReceipt(matches.length === 0
      ? 'В списке пока нет новой заявки с отправленными данными. Обновите статус позже. Для отдельной заявки начните новую подачу.'
      : 'Найдено несколько новых заявок с этими данными. Сверьте их номера перед отдельной подачей.')
  }

  startNew.addEventListener('click', () => {
    if (busy || !attempt || !canStartNew || !sameActor(actorVersion)) return
    forgetAttempt()
    showReceipt('')
    display()
    fields.venueName.focus()
  })

  function persistDraft() {
    if (!unsavedDraft || !formActor || getStoredSession()?.userId !== formActor) return
    const key = applicationDraftKey(formActor)
    try {
      if (unsavedDraft.some(Boolean)) sessionStorage.setItem(key, JSON.stringify(unsavedDraft))
      else sessionStorage.removeItem(key)
      unsavedDraft = null
    } catch { /* Keep edits in this screen until tab storage is available. */ }
  }

  function resetDraft() {
    unsavedDraft = null
    if (draftKey) {
      try { sessionStorage.removeItem(draftKey) } catch { /* optional storage */ }
    }
    form.reset()
    discard.hidden = true
  }
  discard.addEventListener('click', () => {
    if (!sameActor(actorVersion) || !window.confirm('Очистить введённые данные заявки?')) return
    resetDraft()
  })
  form.addEventListener('input', () => {
    if (!sameActor(actorVersion) || !draftKey) return
    unsavedDraft = inputs.map(input => input.value)
    discard.hidden = !unsavedDraft.some(Boolean)
    persistDraft()
  })
  root.replaceChildren(header, status, receipt, current, form, history)
  let applications: VenueConnectionApplicationDto[] = []
  let owners: Set<number> | null = null
  let visitAllowsEntry = false
  let busy = false
  let actorVersion = 0

  function setBusy(value: boolean) {
    busy = value
    for (const element of [submit, startNew, discard, refresh, ...Array.from(current.querySelectorAll('button')), ...inputs]) {
      element.disabled = value
    }
    header.setAttribute('aria-busy', String(value))
  }

  function hasLinkedCabinet(item: VenueConnectionApplicationDto) {
    return item.status === 'APPROVED' && item.linkedVenueId != null
  }

  function display(checkingAccess = false) {
    const hasLinked = applications.some(hasLinkedCabinet)
    refresh.hidden = applications.length === 0 && !attempt
    submit.hidden = !!attempt
    startNew.hidden = !canStartNew
    refresh.textContent = hasLinked ? 'Обновить доступ и статус' : 'Обновить статус'
    const currentRows: HTMLElement[] = []
    const pastRows: HTMLElement[] = []
    for (const item of applications) {
      const id = item.linkedVenueId
      const linked = hasLinkedCabinet(item)
      const isOwner = linked && id != null && owners?.has(id) === true
      const isCurrent = item.status === 'PENDING' || (item.status === 'APPROVED' && (!linked || isOwner))
      const card = el('article', { className: 'card guest-application-row' })
      const label = linked ? 'Одобрена. Кабинет создан.' : statusText[item.status]
      append(card, el('h3', { text: `Заявка №${item.id}` }), el('p', { text: `${item.venueName} · ${item.city}` }),
        el('strong', { text: label }))
      if (linked && !isOwner) {
        const accessText = checkingAccess ? 'Проверяем доступ владельца…'
          : owners === null ? 'Пока не удалось проверить доступ владельца.'
            : 'Текущий доступ владельца к этому заведению не подтверждён.'
        card.appendChild(el('p', { text: accessText }))
      } else if (isOwner && visitAllowsEntry) {
        const enter = el('button', { text: 'Перейти в кабинет заведения' })
        enter.addEventListener('click', () => void checkAccess(id))
        card.appendChild(enter)
      }
      const group = isCurrent ? currentRows : pastRows
      group.push(card)
    }
    current.replaceChildren(el('h2', { text: 'Текущие заявки' }), ...currentRows)
    historyList.replaceChildren(...pastRows)
    current.hidden = currentRows.length === 0
    history.hidden = pastRows.length === 0
    historySummary.textContent = `История заявок (${pastRows.length})`
    form.hidden = currentRows.length > 0 || (hasLinked && owners === null)
  }

  function hidePrivateView() {
    draftKey = null
    attempt = null
    canStartNew = false
    showReceipt('')
    status.textContent = ''
    form.reset()
    applications = []
    current.replaceChildren()
    historyList.replaceChildren()
    history.open = false
    refresh.textContent = 'Обновить статус'
    form.hidden = current.hidden = history.hidden = discard.hidden = true
    refresh.hidden = false
    owners = null
    visitAllowsEntry = false
  }

  function sameActor(version: number) {
    if (signal.aborted) return false
    const actor = getTelegramContext().telegramUserId
    if (formActor !== actor) {
      unsavedDraft = null
      formActor = actor
      actorVersion++
      hidePrivateView()
      setBusy(false)
      void load()
    }
    return version === actorVersion
  }

  async function load(): Promise<void> {
    if (!sameActor(actorVersion) || busy) return
    const version = actorVersion
    const actor = formActor
    setBusy(true)
    canStartNew = false
    startNew.hidden = true
    status.textContent = 'Проверяем ваши заявки…'
    const result = await requestApi<VenueConnectionApplicationDto[]>(backendUrl, endpoint, { signal }, deps)
    if (!sameActor(version)) return
    setBusy(false)
    if (!result.ok) {
      if (result.error.status === 401 || result.error.status === 403) hidePrivateView()
      refresh.hidden = false
      status.textContent = failure(result.error, 'Не удалось загрузить заявки. Попробуйте обновить их ещё раз.')
      return
    }
    if (!actor || getStoredSession()?.userId !== actor) {
      hidePrivateView()
      status.textContent = 'Не удалось подтвердить пользователя. Откройте приложение из Telegram ещё раз.'
      return
    }
    if (!draftKey) {
      draftKey = applicationDraftKey(actor)
      try {
        const saved = unsavedDraft ?? JSON.parse(sessionStorage.getItem(draftKey) ?? 'null')
        if (Array.isArray(saved) && saved.length === inputs.length && saved.every(value => typeof value === 'string')) {
          inputs.forEach((input, i) => { input.value = saved[i].slice(0, input.maxLength) })
          discard.hidden = !inputs.some(input => input.value)
        }
      } catch { /* A missing or invalid draft leaves a blank form. */ }
      try {
        const pending = JSON.parse(sessionStorage.getItem(draftKey + ':attempt') ?? 'null')
        if (pending && Array.isArray(pending.values) && pending.values.length === inputs.length &&
            pending.values.every((value: unknown) => typeof value === 'string') &&
            Array.isArray(pending.knownIds) && pending.knownIds.every(Number.isSafeInteger)) attempt = pending
      } catch { /* Ignore malformed optional tab storage. */ }
    }
    applications = result.data
    owners = null
    visitAllowsEntry = false
    reconcileAttempt(applications)
    if (applications.some(hasLinkedCabinet)) {
      display(true)
      await checkAccess()
    } else {
      display()
      status.textContent = applications.length || attempt ? '' : 'У вас пока нет заявок.'
    }
  }

  form.addEventListener('submit', async event => {
    event.preventDefault()
    if (!sameActor(actorVersion) || busy || form.hidden) return
    if (attempt) return void load()
    const version = actorVersion
    const venueName = fields.venueName.value.trim()
    const city = fields.city.value.trim()
    const contact = fields.contact.value.trim()
    if (!venueName || !city || !contact) {
      status.textContent = 'Заполните название заведения, город и контакт для связи.'
      return
    }
    if (!draftKey || !formActor || getStoredSession()?.userId !== formActor) {
      void load()
      return
    }
    const submission = { values: inputs.map(input => input.value), knownIds: applications.map(row => row.id) }
    try {
      sessionStorage.setItem(draftKey + ':attempt', JSON.stringify(submission))
    } catch {
      status.textContent = 'Не удалось сохранить попытку отправки в этой вкладке. Заявка не отправлена. Разрешите хранение данных или откройте Mini App заново.'
      return
    }
    attempt = submission
    showReceipt('')
    setBusy(true)
    status.textContent = 'Отправляем заявку…'
    const result = await requestApi<VenueConnectionApplicationWriteResponse>(backendUrl, endpoint, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal,
      body: JSON.stringify({ venueName, city, contact, comment: fields.comment.value.trim() || null })
    }, deps)
    if (!sameActor(version)) return
    setBusy(false)
    if (!result.ok) {
      if ([400, 401, 403, 413, 415, 422, 429].includes(result.error.status)) {
        forgetAttempt()
        if (result.error.status === 401 || result.error.status === 403) hidePrivateView()
        status.textContent = failure(result.error, 'Заявка не принята. Проверьте данные и повторите отправку.')
      } else {
        display()
        status.textContent = `Не удалось подтвердить отправку. Данные сохранены в форме. Нажмите «${refresh.textContent}».`
      }
      refresh.hidden = false
      return
    }
    const row = result.data.application
    applications = [row, ...applications.filter(item => item.id !== row.id)]
    confirmAttempt()
    display()
    status.textContent = result.data.created ? `Заявка №${row.id} отправлена.` : `Заявка №${row.id} уже принята.`
    status.focus()
  })

  async function checkAccess(target?: number) {
    if (!sameActor(actorVersion) || busy) return
    const version = actorVersion
    setBusy(true)
    status.textContent = 'Проверяем доступ к кабинету…'
    const [me, visit] = await Promise.all([venueGetMe(backendUrl, deps, signal), checkGuestVisit(backendUrl, signal)])
    if (!sameActor(version)) return
    if (!me.ok && me.error.status === 401) {
      hidePrivateView()
      status.textContent = failure(me.error, '')
      setBusy(false)
      return
    }
    owners = null
    visitAllowsEntry = false
    if (!me.ok && me.error.status === 403) {
      owners = new Set()
    } else if (me.ok && isVenueAccessForUser(me.data, getTelegramContext().telegramUserId)) {
      owners = new Set(me.data.venues.filter(venue => venue.role === 'OWNER').map(venue => venue.venueId))
    }
    const hasCabinet = applications.some(item => item.status === 'APPROVED' && item.linkedVenueId && owners?.has(item.linkedVenueId))
    visitAllowsEntry = hasCabinet && visit === 'absent'
    display()
    if (!owners || (hasCabinet && visit === 'unknown')) {
      status.textContent = 'Не удалось проверить доступ и посещение. Нажмите «Обновить доступ и статус».'
    } else if (!hasCabinet) {
      status.textContent = form.hidden ? '' : 'Доступ владельца пока не подтверждён. Можно подать новую заявку.'
    } else if (visit === 'active') {
      status.textContent = 'Чтобы перейти в кабинет, сначала завершите текущее посещение. ' + botVisitExitHint
    } else {
      status.textContent = 'Доступ владельца подтверждён.'
      if (target !== undefined && owners.has(target) &&
          applications.some(item => item.status === 'APPROVED' && item.linkedVenueId === target)) {
        openHomeMode('venue', target)
      }
    }
    setBusy(false)
  }

  form.hidden = current.hidden = history.hidden = true
  refresh.addEventListener('click', () => void load())
  window.addEventListener('focus', () => {
    sameActor(actorVersion)
  }, { signal })
  window.addEventListener('pageshow', event => {
    if (event.persisted) void load()
  }, { signal })
  void load()
  return () => controller.abort()
}
