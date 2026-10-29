import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi, requestBinary } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

export type StaffPhoto = {
  id: string; state: string; consent: 'NONE' | 'SELF' | 'OWNER_RECORDED'; consentAt: string | null
  previewUrl: string; width: number; height: number
}
export type StaffPhotoState = {
  enabled: boolean; revision: number; current: StaffPhoto | null; candidate: StaffPhoto | null
  canManage: boolean; canConsent: boolean; isSelf: boolean; displayOnly: boolean
  guestVisible: boolean; cleanupPending: boolean; uploading: boolean; consentPolicy: string
}
export type StaffPhotoNavigation = { guard: () => boolean; hasPending: () => boolean; refresh: () => void }
export type StaffPhotoPanel = { element: HTMLElement; refresh: () => Promise<void>; canLeave: () => boolean; hasPending: () => boolean; dispose: () => void }

export function createStaffPhotoPanel(backendUrl: string, isDebug: boolean, venueId: number, profileId: number, initial?: StaffPhotoState, onChange: () => void = () => undefined): StaffPhotoPanel {
  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}/staff/profiles/${profileId}/photos`
  const element = el('section', { className: 'staff-photo-panel' })
  element.setAttribute('aria-label', 'Фотография сотрудника')
  const body = el('div', { className: 'staff-photo-body' })
  const status = el('p', { className: 'staff-photo-status', text: 'Загрузка состояния фото…' })
  status.setAttribute('aria-live', 'polite')
  const actions = el('div', { className: 'staff-photo-actions' })
  append(element, el('h4', { text: 'Фотография' }), body, status, actions)
  let state: StaffPhotoState | null = initial ?? null
  let disposed = false
  let busy = false
  let uncertain = false
  let selected: File | null = null
  let requestId: string | null = null
  let requestRevision = 0
  let selectedUrl: string | null = null
  let readAbort: AbortController | null = null
  let writeAbort: AbortController | null = null
  let renderAbort: AbortController | null = null
  const urls = new Set<string>()
  const revokeImages = () => { urls.forEach((url) => URL.revokeObjectURL(url)); urls.clear() }
  const clearSelection = () => {
    selected = null; requestId = null
    if (selectedUrl) URL.revokeObjectURL(selectedUrl)
    selectedUrl = null
  }
  const button = (label: string, action: () => void, disabled = busy) => {
    const control = el('button', { className: 'button-small button-secondary', text: label }) as HTMLButtonElement
    control.disabled = disabled
    control.addEventListener('click', action)
    return control
  }
  const showError = (code?: string, message?: string) => {
    status.textContent = code === 'STAFF_PHOTO_UNAVAILABLE' ? 'Загрузка фотографий временно недоступна'
      : code === 'FORBIDDEN' ? 'Действие недоступно для этой карточки. Обновите состояние или обратитесь к владельцу.'
        : message || 'Ответ не получен. Проверьте состояние фото перед повторной загрузкой.'
  }
  const loadImage = async (photo: StaffPhoto, target: HTMLElement, signal: AbortSignal) => {
    const expected = `${path}/${photo.id}/content`
    if (photo.previewUrl !== expected || !/^[a-f0-9-]{36}$/.test(photo.id)) return
    const result = await requestBinary(backendUrl, expected, { signal }, deps)
    if (disposed || signal.aborted) return
    if (!result.ok) { target.textContent = 'Фото недоступно. Проверьте состояние.'; return }
    const url = URL.createObjectURL(result.data.blob); urls.add(url)
    const img = el('img', { className: 'staff-photo-image' }) as HTMLImageElement
    img.src = url; img.alt = 'Фото сотрудника'; target.replaceChildren(img)
  }
  const deletePhoto = async () => {
    if (!state?.canManage || busy || uncertain || selected) return
    if (!window.confirm('Удалить все фото этой карточки? Показ гостям прекратится сразу; восстановить фото можно только новой загрузкой.')) return
    readAbort?.abort(); readAbort = null
    busy = true; render(); status.textContent = 'Сохраняем состояние фото…'
    const controller = new AbortController(); writeAbort = controller
    const result = await requestApi<StaffPhotoState>(backendUrl, `${path}/delete`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, signal: controller.signal,
      body: JSON.stringify({ revision: state.revision })
    }, deps)
    if (disposed || writeAbort !== controller) return
    writeAbort = null; busy = false
    if (result.ok) { state = result.data; uncertain = false; render() }
    else { uncertain = true; render(); showError(result.error.code, result.error.message) }
  }
  const upload = async () => {
    if (disposed || !state || !selected || busy || !state.enabled || uncertain || state.uploading || !state.canManage) return
    requestId ??= crypto.randomUUID()
    readAbort?.abort(); readAbort = null
    busy = true; render(); status.textContent = 'Загружаем фото. Текущее фото сохранится до завершения загрузки.'
    const controller = new AbortController(); writeAbort = controller
    const result = await requestApi<StaffPhotoState>(backendUrl, path, {
      method: 'POST', headers: { 'Content-Type': selected.type || 'application/octet-stream', 'Idempotency-Key': requestId, 'X-Photo-Revision': String(requestRevision) },
      body: selected, signal: controller.signal
    }, deps)
    if (disposed || writeAbort !== controller) return
    writeAbort = null; busy = false
    if (result.ok) { state = result.data; clearSelection(); uncertain = false; render(); status.textContent = 'Фото сохранено и стало текущим. Показ гостям зависит от видимости карточки и настроек команды.' }
    else {
      uncertain = true
      if (result.error.status > 0 && result.error.status < 500) clearSelection()
      render(); showError(result.error.code, result.error.message)
    }
  }
  const render = () => {
    queueMicrotask(onChange)
    renderAbort?.abort(); renderAbort = new AbortController(); revokeImages()
    body.replaceChildren(); actions.replaceChildren()
    if (selected && selectedUrl) {
      const image = el('img', { className: 'staff-photo-image' }) as HTMLImageElement
      image.src = selectedUrl; image.alt = 'Предпросмотр выбранного фото'
      append(body, image, el('p', { text: busy ? 'Фото загружается. Дождитесь результата.' : 'Результат загрузки не подтверждён. Проверьте состояние перед повтором.' }))
      if (!busy) actions.appendChild(button('Отменить выбор', () => { clearSelection(); render(); status.textContent = 'Локальный выбор отменён. Это не удаляет уже загруженное фото; проверьте состояние.' }))
    }
    if (!state) { actions.appendChild(button('Проверить состояние фото', () => void refresh())); return }
    const photos = [state.current, state.candidate].filter((item): item is StaffPhoto => !!item)
    if (!photos.length) body.appendChild(el('div', { className: 'staff-photo-placeholder', text: 'Фото не добавлено' }))
    photos.forEach((photo) => {
      const card = el('div', { className: 'staff-photo-version' })
      const preview = el('div', { className: 'staff-photo-placeholder', text: 'Фото загружается…' })
      const isCandidate = photo.id === state?.candidate?.id
      const label = isCandidate ? 'Ранее загруженное фото · приватно' : state?.guestVisible ? 'Фото видно гостям' : 'Фото сейчас не показывается гостям'
      append(card, preview, el('p', { text: label }))
      void loadImage(photo, preview, renderAbort!.signal)
      body.appendChild(card)
    })
    if (state.candidate) body.appendChild(el('p', { className: 'venue-order-sub', text: state.canManage ? 'Это фото осталось от прежнего процесса и не будет показано автоматически. Для замены выберите фото заново или удалите фотографии карточки.' : 'Это фото осталось от прежнего процесса и не будет показано автоматически. Заменить или удалить его может владелец или менеджер.' }))
    status.textContent = !state.enabled ? 'Загрузка фотографий временно недоступна'
      : state.uploading ? 'Результат предыдущей загрузки ещё проверяется. Обновите состояние; запрос не повторяется автоматически.'
        : state.cleanupPending ? 'Показ удалённого фото прекращён. Физическое удаление из хранилища ещё не подтверждено.' : ''
    if (state.canManage) {
      if (state.enabled) {
        const input = document.createElement('input'); input.type = 'file'; input.accept = 'image/jpeg,image/png'; input.hidden = true
        input.setAttribute('aria-label', 'Выбрать фотографию сотрудника')
        input.addEventListener('change', () => {
          const file = input.files?.[0]; if (disposed || !file || busy || selected || uncertain || !state?.enabled || !state.canManage || state.uploading) return
          if (file.size > 5 * 1024 * 1024) { status.textContent = 'Выберите фото не больше 5 МБ.'; return }
          if (!['image/jpeg', 'image/png'].includes(file.type)) { status.textContent = 'Поддерживаются JPEG и PNG. HEIC/HEIF и WebP экспортируйте в JPEG.'; return }
          clearSelection(); selected = file; requestRevision = state!.revision; selectedUrl = URL.createObjectURL(file); uncertain = false; void upload()
        })
        if (!selected) append(actions, input, button(photos.length ? 'Заменить фото' : 'Добавить фото', () => input.click(), busy || uncertain || !!selected || state.uploading))
        if (selected && selectedUrl && !busy) {
          actions.appendChild(button('Повторить загрузку безопасно', () => void upload(), busy || uncertain || state.uploading))
        }
        body.appendChild(el('p', { className: 'venue-order-sub', text: 'JPEG или PNG, до 5 МБ и 4096 px по стороне. HEIC/HEIF и WebP пока не поддерживаются.' }))
      }
      if (photos.length || state.uploading) actions.appendChild(button('Удалить фото', () => void deletePhoto(), busy || uncertain || !!selected))
      body.appendChild(el('p', { className: 'venue-order-sub', text: 'После загрузки фото сразу станет текущим. Гости увидят его только по действующим настройкам показа карточки и команды.' }))
    } else body.appendChild(el('p', { className: 'venue-order-sub', text: 'Фотографией управляют владелец и менеджер заведения. Вам доступен просмотр своей карточки.' }))
    actions.appendChild(button('Проверить состояние фото', () => void refresh()))
  }
  const refresh = async () => {
    if (disposed || busy) return
    readAbort?.abort(); const controller = new AbortController(); readAbort = controller
    status.textContent = 'Проверяем состояние фото…'
    const result = await requestApi<StaffPhotoState>(backendUrl, path, { signal: controller.signal, cache: 'no-store' }, deps)
    if (disposed || readAbort !== controller) return
    readAbort = null
    if (result.ok) { state = result.data; uncertain = false; if (selected && !requestId && requestRevision !== state.revision) {
      clearSelection(); render(); status.textContent = 'Карточка изменилась. Выберите фотографию заново для текущего сотрудника.'
    } else { if (!selected && !requestId) requestRevision = state.revision; render() } }
    else { state = null; render(); showError(result.error.code, 'Не удалось получить состояние фото. Попробуйте обновить.') }
  }
  const canLeave = () => {
    if (busy) { status.textContent = 'Дождитесь результата операции с фото.'; return false }
    if (selected) { status.textContent = 'Проверьте результат загрузки или нажмите «Отменить выбор».'; return false }
    return true
  }
  const beforeUnload = (event: BeforeUnloadEvent) => { if (busy || selected) { event.preventDefault(); event.returnValue = '' } }
  const onReturn = () => {
    if (document.visibilityState === 'visible' && element.isConnected && !element.closest('[hidden]')) void refresh()
  }
  window.addEventListener('focus', onReturn)
  document.addEventListener('visibilitychange', onReturn)
  window.addEventListener('beforeunload', beforeUnload)
  if (state) render(); else { render(); void refresh() }
  return { element, refresh, canLeave, hasPending: () => busy || !!selected, dispose: () => {
    disposed = true; readAbort?.abort(); writeAbort?.abort(); renderAbort?.abort(); revokeImages(); clearSelection(); window.removeEventListener('beforeunload', beforeUnload); window.removeEventListener('focus', onReturn); document.removeEventListener('visibilitychange', onReturn)
  } }
}

export function renderOwnStaffPhotoScreen(root: HTMLElement, backendUrl: string, isDebug: boolean, venueId: number, setNavigation: (navigation: StaffPhotoNavigation) => void, onChange: () => void = () => undefined) {
  const controller = new AbortController(); let disposed = false
  const panels: StaffPhotoPanel[] = []
  root.replaceChildren(el('h2', { text: 'Моя карточка' }))
  const body = el('div', { className: 'staff-own-photos' }); root.appendChild(body)
  body.textContent = 'Загрузка…'; setNavigation({ guard: () => panels.every((panel) => panel.canLeave()), hasPending: () => panels.some((panel) => panel.hasPending()), refresh: () => panels.forEach((panel) => void panel.refresh()) })
  void requestApi<Array<{ profileId: number; displayName: string; photo: StaffPhotoState }>>(backendUrl, `/api/venue/${venueId}/staff/photos/me`, { signal: controller.signal, cache: 'no-store' }, { isDebug, getAccessToken, clearSession }).then((result) => {
    if (disposed) return
    body.replaceChildren()
    if (!result.ok) { body.textContent = 'Не удалось загрузить собственную карточку. Откройте раздел ещё раз.'; return }
    if (!result.data.length) { body.textContent = 'Связанной карточки пока нет. Обратитесь к владельцу заведения.'; return }
    result.data.forEach((item) => {
      const card = el('section', { className: 'card' }); card.appendChild(el('h3', { text: item.displayName }))
      const panel = createStaffPhotoPanel(backendUrl, isDebug, venueId, item.profileId, item.photo, onChange); panels.push(panel)
      card.appendChild(panel.element); body.appendChild(card)
    })
  })
  return () => { disposed = true; controller.abort(); panels.forEach((panel) => panel.dispose()) }
}
