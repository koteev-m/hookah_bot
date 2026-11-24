import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

type LinkSettings = {
  venueId: number; name: string; address: string; slug: string | null
  catalogListed: boolean; canManage: boolean; guestAvailable: boolean; url: string | null
  telegramUnavailableReason?: string | null
  guestUnavailableReason?: string | null
  telegramUrl?: string | null
}

export function mountVenuePublicLink(root: HTMLElement, backendUrl: string, isDebug: boolean, venueId: number, onChange?: () => void) {
  const controller = new AbortController()
  const section = el('section', { className: 'card venue-public-link' })
  const title = el('h3', { text: 'Ссылка заведения' })
  const identity = el('p')
  const telegramLink = document.createElement('input')
  telegramLink.readOnly = true
  telegramLink.className = 'venue-input'
  telegramLink.setAttribute('aria-label', 'Персональная ссылка в Telegram')
  const copyTelegram = el('button', { text: 'Скопировать ссылку' })
  const telegramHint = el('p')
  const status = el('p', { text: 'Загружаем ссылку…' })
  status.setAttribute('role', 'status')
  const availability = el('p')
  const extra = el('details')
  extra.hidden = true
  append(extra, el('summary', { text: 'Дополнительные настройки ссылки' }))
  const slug = document.createElement('input')
  slug.maxLength = 63
  slug.className = 'venue-input'
  slug.setAttribute('aria-label', 'Адрес ссылки')
  const listed = document.createElement('input')
  listed.type = 'checkbox'
  const label = el('label', { text: 'Показывать в общем каталоге' })
  label.prepend(listed)
  const hint = el('p', { text: 'Не показывать в каталоге: заведение остаётся доступно по ссылке при выполнении условий публикации. Ссылка не даёт права заказывать к столу.' })
  const save = el('button', { text: 'Сохранить настройки ссылки' })
  append(extra, slug, label, hint, save)
  append(section, title, identity, telegramLink, copyTelegram, telegramHint, status, availability, extra)
  save.disabled = true
  root.appendChild(section)
  let settings: LinkSettings | null = null
  let saving = false
  let requestId = 0
  let savedIdentity: string | null = null
  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}/public-link`
  const showAvailability = (data: LinkSettings) => {
    const reasons: Record<string, string> = {
      PUBLICATION: 'Гостевой доступ появится после публикации при действующей подписке. Проверьте статус в разделе «Публикация заведения».',
      SUBSCRIPTION: 'Гостевой доступ закрыт по подписке. Проверьте её статус в разделе «Подписка».',
      MAINTENANCE: 'Гостевой доступ временно закрыт на время обслуживания.',
      UNVERIFIED: 'Не удалось проверить гостевой доступ. Повторите проверку позже.'
    }
    availability.textContent = !data.guestAvailable
      ? reasons[data.guestUnavailableReason ?? ''] ?? 'Гостевой доступ сейчас закрыт. Проверьте публикацию и подписку.'
      : data.catalogListed ? 'Заведение доступно гостям и показывается в каталоге.' : 'Не показывать в каталоге. Гости могут открыть заведение по ссылке.'
  }
  const directLink = (data: LinkSettings) => {
    const url = data.telegramUrl ?? ''
    const match = /^https:\/\/t\.me\/[a-zA-Z0-9_]{5,32}\?startapp=venue_([0-9a-z]+)$/.exec(url)
    return data.venueId === venueId && match?.[1] === venueId.toString(36) ? url : ''
  }
  const showLinks = (data: LinkSettings) => {
    const url = directLink(data)
    telegramLink.value = url
    telegramLink.hidden = !url
    copyTelegram.disabled = !url || !data.guestAvailable
    if (url) {
      telegramHint.textContent = data.guestAvailable
        ? 'Открывает это заведение прямо в Mini App внутри Telegram.'
        : 'Ссылку можно будет скопировать, когда откроется гостевой доступ.'
    } else if (data.telegramUnavailableReason === 'MAIN_MINI_APP_UNCONFIRMED') {
      telegramHint.textContent = 'Прямой запуск выключен в настройках этого окружения. Для его включения нужно подтвердить адрес Main Mini App у бота и включить TELEGRAM_MAIN_MINI_APP_CONFIGURED.'
    } else if (data.telegramUnavailableReason === 'ENVIRONMENT') {
      telegramHint.textContent = 'Для этого окружения не настроена корректная пара Telegram-бота и HTTPS-адреса Mini App.'
    } else {
      telegramHint.textContent = 'Сервер не вернул прямую ссылку на это заведение. Проверьте настройку Main Mini App для этого окружения.'
    }
  }
  const show = (data: LinkSettings) => {
    settings = data
    identity.textContent = savedIdentity ?? `${data.name} · ${data.address || 'Адрес пока не указан'}`
    showLinks(data)
    slug.value = data.slug ?? ''
    listed.checked = data.catalogListed
    extra.hidden = !data.canManage
    save.disabled = !data.canManage
    showAvailability(data)
    status.textContent = ''
  }
  copyTelegram.disabled = true
  telegramLink.hidden = true
  const copyUrl = async (url: string, input: HTMLInputElement) => {
    try {
      await navigator.clipboard.writeText(url)
      status.textContent = 'Ссылка скопирована.'
    } catch {
      input.focus(); input.select()
      status.textContent = 'Выделили ссылку. Нажмите и удерживайте поле, затем выберите «Скопировать».'
    }
  }
  copyTelegram.addEventListener('click', async () => {
    if (!settings?.guestAvailable) return
    const url = directLink(settings)
    if (url) await copyUrl(url, telegramLink)
  })
  save.addEventListener('click', async () => {
    if (!settings?.canManage || saving) return
    if (slug.value !== settings.slug && (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(slug.value) || slug.value.length > 63)) {
      status.textContent = 'Новый адрес: до 63 символов, строчные латинские буквы, цифры и одиночные дефисы между ними.'
      return
    }
    if (!window.confirm('Сохранить адрес и видимость выбранного заведения? Старые ссылки продолжат вести в это заведение и не смогут перейти другому. Это не меняет публикацию или текущие визиты.')) return
    saving = true; save.disabled = true; requestId++
    onChange?.()
    const result = await requestApi<LinkSettings>(backendUrl, path, {
      method: 'PUT', signal: controller.signal, headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ expectedSlug: settings.slug, slug: slug.value, catalogListed: listed.checked, confirmed: true })
    }, deps)
    saving = false; save.disabled = false
    if (controller.signal.aborted) return
    if (result.ok) { show(result.data); status.textContent = 'Настройки ссылки сохранены.' }
    else status.textContent = result.error.message || 'Не удалось сохранить настройки. Обновите страницу и повторите.'
    onChange?.()
  })
  const refresh = async () => {
    const id = ++requestId
    const result = await requestApi<LinkSettings>(backendUrl, path, { signal: controller.signal }, deps)
    if (controller.signal.aborted || id !== requestId || saving) return
    if (result.ok) {
      if (settings) {
        settings = { ...settings, telegramUrl: result.data.telegramUrl, guestAvailable: result.data.guestAvailable, venueId: result.data.venueId }
        showLinks(result.data)
        showAvailability(result.data)
      }
      else show(result.data)
    } else if (settings) {
      settings = { ...settings, guestAvailable: false }
      showLinks(settings)
      availability.textContent = 'Не удалось обновить доступность ссылки. Откройте карточку ещё раз.'
    } else {
      status.textContent = 'Не удалось загрузить ссылку выбранного заведения. Обновите страницу и повторите.'
    }
  }
  void refresh()
  const updateIdentity = (event: Event) => {
    const detail = (event as CustomEvent<{ venueId: number; name: string; displayAddress?: string | null; city?: string | null; address?: string | null }>).detail
    if (detail?.venueId !== venueId) return
    const address = detail.displayAddress || [detail.city, detail.address].filter(Boolean).join(', ') || 'Адрес пока не указан'
    savedIdentity = `${detail.name} · ${address}`
    identity.textContent = savedIdentity
  }
  window.addEventListener('venue-public-card-saved', updateIdentity)
  return {
    refresh,
    isDirty: () => Boolean(settings?.canManage && (slug.value !== (settings.slug ?? '') || listed.checked !== settings.catalogListed)),
    isSaving: () => saving,
    dispose: () => { controller.abort(); window.removeEventListener('venue-public-card-saved', updateIdentity); section.remove() }
  }
}
