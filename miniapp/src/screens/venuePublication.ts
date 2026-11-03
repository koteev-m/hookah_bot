import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

const requirements = {
  NAME: ['Название', 'Укажите название в профиле заведения в Telegram-боте.'],
  ADDRESS: ['Адрес', 'Укажите и сохраните адрес в карточке заведения.'],
  HOURS: ['Часы работы', 'В настройках «Часы работы» заполните все семь дней, включая выходные.'],
  DESCRIPTION: ['Краткое описание', 'Добавьте и сохраните краткое описание в карточке заведения.'],
  MENU: ['Меню', 'Добавьте хотя бы одну доступную позицию в активный раздел меню.']
} as const

const statuses: Record<string, string> = {
  DRAFT: 'Черновик',
  PUBLISHED: 'Опубликовано',
  HIDDEN: 'Скрыто',
  PAUSED: 'На паузе',
  SUSPENDED: 'Заблокировано',
  ARCHIVED: 'Архив',
  DELETED: 'Удалено'
}

type Publication = {
  name: string
  status: string | null
  ownerCanPublish: boolean
  missing?: Array<keyof typeof requirements> | null
  guestAccess: 'AVAILABLE' | 'UNAVAILABLE' | 'UNVERIFIED'
  outcome?: 'PUBLISHED' | 'ALREADY_PUBLISHED' | 'NOT_READY' | 'INVALID_STATUS'
}

export function mountVenuePublication(options: {
  root: HTMLElement
  backendUrl: string
  isDebug: boolean
  venueId: number
  initialStatus?: string | null
  blocked: () => string
  onStatusChanged: (status: string | null) => void
  onBusyChanged: () => void
}) {
  const { root, backendUrl, isDebug, venueId, initialStatus, blocked, onStatusChanged, onBusyChanged } = options
  const controller = new AbortController()
  const section = el('section', { className: 'card venue-publication' })
  section.dataset.testid = 'venue-publication'
  const title = el('h3', { text: 'Публикация заведения' })
  const status = el('p')
  const guestAccess = el('p')
  const message = el('p', { text: 'Загружаем статус публикации…' })
  message.setAttribute('role', 'status')
  const issues = el('ul')
  issues.hidden = true
  const actions = el('div', { className: 'venue-settings-links' })
  const check = el('button', { className: 'venue-owner-action', text: 'Проверить готовность' })
  const publish = el('button', { className: 'venue-owner-action', text: 'Опубликовать заведение' })
  check.type = publish.type = 'button'
  check.hidden = publish.hidden = true
  append(actions, check, publish)
  append(section, title, status, guestAccess, message, issues, actions)
  root.appendChild(section)

  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}/publication`
  let data: Publication | null = null
  let busy = false
  let publishing = false
  let feedback = ''
  let needsReload = false
  let revision = 0

  function render() {
    const statusLabel = data?.status && Object.hasOwn(statuses, data.status) ? statuses[data.status] : 'Статус недоступен'
    status.textContent = data ? `Статус: ${statusLabel}` : ''
    guestAccess.textContent = !data ? '' : data.guestAccess === 'AVAILABLE'
      ? 'Гостевой доступ: открыт.'
      : data.guestAccess === 'UNVERIFIED'
        ? 'Не удалось проверить гостевой доступ. Обновите статус перед отправкой ссылки.'
        : data.status === 'PUBLISHED'
          ? 'Гостевой доступ закрыт. Проверьте подписку или обратитесь в поддержку платформы.'
          : 'Гостевой доступ: закрыт.'
    const isDraft = data?.status === 'DRAFT'
    const blockingMessage = isDraft ? blocked() : ''
    const missing = isDraft && !blockingMessage ? data?.missing : null
    const ready = missing?.length === 0
    const guidance = isDraft && !data?.ownerCanPublish ? 'Опубликовать заведение может только его владелец.' : ''
    const statusGuidance = data?.status && !isDraft && data.status !== 'PUBLISHED'
      ? 'Из этого статуса публикация в настройках недоступна. Обратитесь в поддержку платформы.'
      : ''
    message.textContent = feedback || blockingMessage || [
      ready ? 'Заведение готово к публикации.' : '', guidance, statusGuidance
    ].filter(Boolean).join(' ')
    message.hidden = !message.textContent
    issues.replaceChildren(...(missing ?? []).flatMap(key => {
      if (!Object.hasOwn(requirements, key)) return []
      const [label, instruction] = requirements[key]
      return [el('li', { text: `${label}: ${instruction}` })]
    }))
    issues.hidden = issues.childElementCount === 0
    check.hidden = !(isDraft || needsReload || data?.guestAccess === 'UNVERIFIED')
    check.textContent = needsReload || !isDraft ? 'Обновить статус' : 'Проверить готовность'
    check.disabled = busy || (!needsReload && Boolean(blockingMessage))
    publish.hidden = !(isDraft && ready && data?.ownerCanPublish && !needsReload)
    publish.disabled = busy || Boolean(blockingMessage)
  }

  function draftChanged() {
    if (data?.status !== 'DRAFT') return
    if (blocked()) {
      revision++
      data = { ...data, missing: null }
      if (!busy) feedback = ''
    }
    render()
  }

  async function load(checkReadiness: boolean) {
    if (busy) return
    if (checkReadiness && blocked()) { render(); return }
    const requestedRevision = revision
    busy = true
    feedback = checkReadiness ? 'Проверяем готовность…' : 'Загружаем статус публикации…'
    render()
    const result = await requestApi<Publication>(backendUrl, path + (checkReadiness ? '?check=true' : ''), {
      signal: controller.signal
    }, deps)
    if (controller.signal.aborted) return
    busy = false
    needsReload = !result.ok
    if (result.ok) {
      const previousStatus = data ? data.status : initialStatus
      const changed = previousStatus !== result.data.status || (data && data.guestAccess !== result.data.guestAccess)
      data = requestedRevision === revision ? result.data : { ...result.data, missing: null }
      feedback = ''
      if (changed) onStatusChanged(data.status)
    } else {
      if (data) data = { ...data, missing: null }
      feedback = 'Не удалось проверить статус публикации. Обновите статус и повторите.'
    }
    render()
  }

  async function confirmPublish() {
    if (busy || needsReload || blocked() || data?.status !== 'DRAFT' || !data.ownerCanPublish || data.missing?.length !== 0) return
    if (!window.confirm(`Опубликовать «${data.name}»? Будет опубликована сохранённая карточка. Гостевой доступ зависит от подписки и настроек платформы. Видимость в каталоге сохранится.`)) return
    const requestedRevision = revision
    busy = publishing = true
    onBusyChanged()
    feedback = 'Публикуем заведение…'
    render()
    const result = await requestApi<Publication>(backendUrl, path, {
      method: 'POST', signal: controller.signal, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ confirmed: true })
    }, deps)
    if (controller.signal.aborted) return
    busy = publishing = false
    onBusyChanged()
    needsReload = !result.ok
    if (result.ok) {
      data = requestedRevision === revision ? result.data : { ...result.data, missing: null }
      feedback = data.outcome === 'PUBLISHED' ? 'Заведение опубликовано.'
        : data.outcome === 'ALREADY_PUBLISHED' ? 'Заведение уже опубликовано.'
          : data.outcome === 'NOT_READY' ? 'Данные изменились. Исправьте условия и повторите проверку готовности.'
            : 'Статус изменился. Публикация из текущего статуса недоступна.'
      if (data.status !== 'DRAFT') onStatusChanged(data.status)
    } else {
      data = { ...data, missing: null }
      feedback = result.error.status === 403
        ? 'Публикация недоступна. Проверьте роль в выбранном заведении и обновите статус.'
        : 'Не удалось подтвердить результат публикации. Обновите статус перед повторной попыткой.'
    }
    render()
  }

  check.addEventListener('click', () => void load(!needsReload && data?.status === 'DRAFT'))
  publish.addEventListener('click', () => void confirmPublish())
  void load(false)

  return {
    draftChanged,
    isPublishing: () => publishing,
    dispose: () => { controller.abort(); section.remove() }
  }
}
