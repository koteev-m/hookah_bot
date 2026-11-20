import { clearSession, getAccessToken, getStoredSession } from '../shared/api/auth'
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

function failure(error: ApiErrorInfo, fallback: string) {
  if (error.status === 401) return 'Сессия истекла. Откройте приложение из Telegram ещё раз.'
  if (error.status === 429) return 'Слишком много запросов. Подождите несколько минут и попробуйте снова.'
  return fallback
}

export function renderGuestVenueApplication(root: HTMLElement, backendUrl: string, isDebug: boolean, onBack: () => void) {
  const controller = new AbortController()
  const { signal } = controller
  const deps = { clearSession, getAccessToken, isDebug }
  const back = el('button', { className: 'button-small button-secondary', text: 'Назад' })
  back.addEventListener('click', onBack)
  const header = el('section', { className: 'card' })
  append(header, back, el('h1', { text: 'Для заведений' }),
    el('p', { text: 'Расскажите о своей кальянной. Доступ к кабинету появится после рассмотрения заявки и создания заведения платформой.' }))
  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  status.tabIndex = -1
  const list = el('div', { className: 'venue-orders-list' })
  const refresh = el('button', { className: 'button-secondary', text: 'Обновить статус' })
  const access = el('button', { className: 'button-secondary', text: 'Обновить доступ' })
  const actions = el('div', { className: 'venue-inline-actions' })
  append(actions, refresh, access)
  const form = el('form', { className: 'card ownership-application-form' })
  const fields = venueApplicationFields('guest-application', 'Дополнительная информация (необязательно)')
  const submit = el('button', { text: 'Отправить заявку' })
  submit.type = 'submit'
  const discard = el('button', { className: 'button-secondary', text: 'Очистить черновик' })
  discard.type = 'button'
  discard.hidden = true
  append(form, el('h2', { text: 'Заявка на подключение' }), fields.formGrid, submit, discard)
  const inputs = [fields.venueName, fields.city, fields.contact, fields.comment]
  let formActor = getTelegramContext().telegramUserId
  let draftKey: string | null = null
  function resetDraft() {
    if (draftKey) {
      try { sessionStorage.removeItem(draftKey) } catch { /* optional storage */ }
    }
    form.reset()
    discard.hidden = true
  }
  discard.addEventListener('click', () => {
    if (!window.confirm('Очистить введённые данные заявки?')) return
    resetDraft()
  })
  form.addEventListener('input', () => {
    const values = inputs.map(input => input.value)
    discard.hidden = !values.some(Boolean)
    if (!draftKey || getStoredSession()?.userId !== formActor) return
    try {
      if (discard.hidden) sessionStorage.removeItem(draftKey)
      else sessionStorage.setItem(draftKey, JSON.stringify(values))
    } catch { /* The form remains editable without storage. */ }
  })
  root.replaceChildren(header, status, list, actions, form)
  let applications: VenueConnectionApplicationDto[] = []
  let owners: Set<number> | null = null
  let visitAllowsEntry = false
  let busy = false

  function setBusy(value: boolean) {
    busy = value
    for (const element of [submit, discard, refresh, access, ...Array.from(list.querySelectorAll('button')), fields.venueName, fields.city, fields.contact, fields.comment]) {
      element.disabled = value
    }
    root.setAttribute('aria-busy', String(value))
  }

  function display() {
    form.hidden = applications.some(item => item.status === 'PENDING' ||
      (item.status === 'APPROVED' && (!item.linkedVenueId || owners === null || owners.has(item.linkedVenueId))))
    list.replaceChildren(...applications.map(item => {
      const card = el('article', { className: 'card' })
      const label = item.status === 'APPROVED' && item.linkedVenueId ? 'Одобрена. Кабинет создан.' : statusText[item.status]
      append(card, el('h2', { text: `Заявка №${item.id}` }), el('p', { text: `${item.venueName} · ${item.city}` }),
        el('strong', { text: label }))
      const id = item.linkedVenueId
      if (item.status === 'APPROVED' && id && owners !== null) {
        if (!owners.has(id)) {
          append(card, el('p', { text: 'Сейчас у вас нет доступа владельца к этому заведению.' +
            (form.hidden ? '' : ' Можно подать новую заявку.') }))
        } else if (visitAllowsEntry) {
          const enter = el('button', { text: 'Перейти в кабинет заведения' })
          enter.addEventListener('click', () => void checkAccess(id))
          card.appendChild(enter)
        }
      }
      return card
    }))
    access.hidden = !applications.some(item => item.status === 'APPROVED' && item.linkedVenueId)
  }

  function syncActor() {
    const actor = getTelegramContext().telegramUserId
    if (actor !== formActor) {
      formActor = actor
      draftKey = null
      form.reset()
      applications = []
      list.replaceChildren()
      form.hidden = access.hidden = discard.hidden = true
      owners = null
      visitAllowsEntry = false
    }
    return actor
  }

  async function load(): Promise<void> {
    if (busy) return
    const actor = syncActor()
    setBusy(true)
    status.textContent = 'Проверяем ваши заявки…'
    const result = await requestApi<VenueConnectionApplicationDto[]>(backendUrl, endpoint, { signal }, deps)
    if (signal.aborted) return
    setBusy(false)
    if (syncActor() !== actor) return load()
    if (!result.ok) {
      status.textContent = failure(result.error, 'Не удалось загрузить заявки. Нажмите «Обновить статус», чтобы повторить.')
      return
    }
    if (actor && getStoredSession()?.userId === actor && !draftKey) {
      draftKey = `hookah_guest_venue_application:user:${actor}`
      try {
        const saved = JSON.parse(sessionStorage.getItem(draftKey) ?? 'null')
        if (Array.isArray(saved) && saved.length === inputs.length && saved.every(value => typeof value === 'string')) {
          inputs.forEach((input, i) => { input.value = saved[i].slice(0, input.maxLength) })
          discard.hidden = !inputs.some(input => input.value)
        }
      } catch { /* A missing or invalid draft leaves a blank form. */ }
    }
    applications = result.data
    owners = null
    visitAllowsEntry = false
    display()
    status.textContent = applications.length ? '' : 'У вас пока нет заявок.'
    if (!access.hidden) await checkAccess()
  }

  form.addEventListener('submit', async event => {
    event.preventDefault()
    if (busy || form.hidden) return
    if (formActor !== getTelegramContext().telegramUserId) {
      form.reset()
      form.hidden = true
      void load()
      return
    }
    const submittedBy = formActor
    const venueName = fields.venueName.value.trim()
    const city = fields.city.value.trim()
    const contact = fields.contact.value.trim()
    if (!venueName || !city || !contact) {
      status.textContent = 'Заполните название заведения, город и контакт для связи.'
      return
    }
    setBusy(true)
    status.textContent = 'Отправляем заявку…'
    const result = await requestApi<VenueConnectionApplicationWriteResponse>(backendUrl, endpoint, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal,
      body: JSON.stringify({ venueName, city, contact, comment: fields.comment.value.trim() || null })
    }, deps)
    if (signal.aborted) return
    setBusy(false)
    if (syncActor() !== submittedBy) return void load()
    if (!result.ok) {
      status.textContent = failure(result.error, 'Не удалось подтвердить отправку. Данные сохранены в форме. Попробуйте отправить заявку ещё раз.')
      return
    }
    const row = result.data.application
    applications = [row, ...applications.filter(item => item.id !== row.id)]
    display()
    resetDraft()
    status.textContent = result.data.created ? `Заявка №${row.id} отправлена.` : `Заявка №${row.id} уже принята.`
    status.focus()
  })

  async function checkAccess(target?: number) {
    if (busy) return
    const checkedActor = formActor
    setBusy(true)
    status.textContent = 'Проверяем доступ к кабинету…'
    const [me, visit] = await Promise.all([venueGetMe(backendUrl, deps, signal), checkGuestVisit(backendUrl, signal)])
    if (signal.aborted) return
    if (syncActor() !== checkedActor) {
      setBusy(false)
      return load()
    }
    owners = null
    visitAllowsEntry = false
    if (!me.ok && me.error.status === 403) {
      owners = new Set()
    } else if (me.ok && isVenueAccessForUser(me.data, getTelegramContext().telegramUserId)) {
      owners = new Set(me.data.venues.filter(venue => venue.role === 'OWNER').map(venue => venue.venueId))
    }
    const hasCabinet = applications.some(item => item.status === 'APPROVED' && item.linkedVenueId && owners?.has(item.linkedVenueId))
    if (!owners || (hasCabinet && visit === 'unknown')) {
      status.textContent = 'Не удалось проверить доступ и посещение. Попробуйте обновить доступ ещё раз.'
    } else if (!hasCabinet) {
      status.textContent = ''
    } else if (visit === 'active') {
      status.textContent = 'Чтобы перейти в кабинет, сначала завершите текущее посещение. ' + botVisitExitHint
    } else {
      visitAllowsEntry = true
      status.textContent = 'Доступ владельца подтверждён.'
      if (target !== undefined && owners.has(target) &&
          applications.some(item => item.status === 'APPROVED' && item.linkedVenueId === target)) {
        openHomeMode('venue', target)
      }
    }
    display()
    setBusy(false)
  }

  form.hidden = access.hidden = true
  refresh.addEventListener('click', () => void load())
  access.addEventListener('click', () => void checkAccess())
  window.addEventListener('focus', () => {
    if (getTelegramContext().telegramUserId !== formActor) {
      syncActor()
      void load()
    }
  }, { signal })
  window.addEventListener('pageshow', event => {
    if (event.persisted) void load()
  }, { signal })
  void load()
  return () => controller.abort()
}
