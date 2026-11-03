import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

type LinkSettings = {
  venueId: number; name: string; address: string; slug: string | null
  catalogListed: boolean; canManage: boolean; guestAvailable: boolean; url: string | null
}

export function mountVenuePublicLink(root: HTMLElement, backendUrl: string, isDebug: boolean, venueId: number, onChange?: () => void) {
  const controller = new AbortController()
  const section = el('section', { className: 'card venue-public-link' })
  const title = el('h3', { text: 'Ссылка заведения' })
  const identity = el('p')
  const link = document.createElement('input')
  link.readOnly = true
  link.className = 'venue-input'
  link.setAttribute('aria-label', 'Персональная ссылка заведения')
  const copy = el('button', { text: 'Скопировать ссылку' })
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
  append(section, title, identity, link, copy, status, availability, extra)
  save.disabled = true
  root.appendChild(section)
  let settings: LinkSettings | null = null
  let saving = false
  let requestId = 0
  let savedIdentity: string | null = null
  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}/public-link`
  const showAvailability = (data: LinkSettings) => {
    availability.textContent = !data.guestAvailable
      ? 'Гостевой доступ сейчас закрыт. Создание ссылки не публикует заведение.'
      : data.catalogListed ? 'Заведение доступно гостям и показывается в каталоге.' : 'Не показывать в каталоге. Гости могут открыть заведение по ссылке.'
  }
  const show = (data: LinkSettings) => {
    settings = data
    identity.textContent = savedIdentity ?? `${data.name} · ${data.address || 'Адрес пока не указан'}`
    link.value = data.url ?? ''
    copy.disabled = !data.url
    slug.value = data.slug ?? ''
    listed.checked = data.catalogListed
    extra.hidden = !data.canManage
    save.disabled = !data.canManage
    showAvailability(data)
    status.textContent = data.url ? '' : 'Ссылка ещё недоступна. Настройку адреса платформы выполняет администратор платформы.'
  }
  copy.disabled = true
  copy.addEventListener('click', async () => {
    if (!settings?.url) return
    try {
      await navigator.clipboard.writeText(settings.url)
      status.textContent = 'Ссылка скопирована.'
    } catch {
      link.focus(); link.select()
      status.textContent = 'Выделили ссылку. Нажмите и удерживайте поле, затем выберите «Скопировать».'
    }
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
      if (settings) showAvailability(result.data)
      else show(result.data)
    } else if (settings) {
      availability.textContent = 'Не удалось обновить доступность ссылки.'
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
