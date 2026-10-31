import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { loadInfoMediaContent } from '../shared/api/infoMediaContent'
import { createInfoMediaPreview } from '../shared/ui/infoMediaPreview'
import { append, el } from '../shared/ui/dom'

type Media = { id: string; type: string; visible: boolean; status: string; sortOrder: number; size: number | null; source: string }
type Section = { id: number; title: string; type: string; visible: boolean; revision: number; media: Media[] }
export type VenueInfoMediaState = { enabled: boolean; sections: Section[] }
type UploadProgress = { index: number; total: number; section: number; pdf: boolean; status: 'WAITING' | 'UPLOADING' | 'SUCCESS' | 'UNKNOWN' | 'FAILED' | 'CANCELLED' }
type Upload = { progress: UploadProgress; file: File; section: number; revision: number; request: string; replace?: string; submitted?: boolean; rejected?: boolean }

export function createVenueInfoMediaEditor(backendUrl: string, isDebug: boolean, venueId: number) {
  const element = el('details', { className: 'venue-info-editor' })
  const content = el('div')
  const status = el('p', { className: 'venue-info-result', text: '' }); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  append(element, el('summary', { text: 'Управлять фотографиями и PDF' }), status, content)
  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}`
  let state: VenueInfoMediaState | null = null
  let busy = false; let disposed = false; let uncertain = false
  let pending: Upload[] = []
  let outcome: 'UNKNOWN' | 'FAILED' | 'SUCCESS' = 'UNKNOWN'
  let progress: UploadProgress[] = []
  const controls = el('div')
  content.appendChild(controls)
  type AttachmentView = { element: HTMLElement; label: HTMLElement; actions: HTMLElement; preview: ReturnType<typeof createInfoMediaPreview> | null }
  type SectionView = { element: HTMLElement; title: HTMLElement; visibility: HTMLElement; actions: HTMLElement; progress: HTMLElement; attachments: HTMLElement; items: Map<string, AttachmentView> }
  const sections = new Map<number, SectionView>()
  const successText = (job: Upload) => job.progress.pdf ? 'PDF загружен' : 'Изображение загружено'
  const markSuccess = (job: Upload) => { job.progress.status = 'SUCCESS' }
  const clearPreviews = () => sections.forEach((section) => section.items.forEach((item) => item.preview?.dispose()))
  const controller = new AbortController()
  let focusTarget: string | null = null
  let updatingDom = false
  const abandonFocus = () => { if (!updatingDom) focusTarget = null }
  for (const event of ['focusin', 'pointerdown', 'keydown']) document.addEventListener(event, abandonFocus, { signal: controller.signal })
  const button = (text: string, action: () => void, disabled = busy || uncertain || pending.length > 0, focusKey = text) => {
    const b = el('button', { className: 'button-small button-secondary', text }) as HTMLButtonElement
    b.dataset.mediaFocus = focusKey
    b.type = 'button'; b.disabled = disabled; b.addEventListener('click', action); return b
  }
  const refresh = async () => {
    if (busy || disposed) return
    busy = true; render(); status.textContent = 'Загружаем материалы…'
    const result = await requestApi<VenueInfoMediaState>(backendUrl, `${path}/info-media`, { signal: controller.signal }, deps)
    if (disposed) return
    busy = false
    if (result.ok) {
      state = result.data
      if (pending.length) {
        busy = true
        const job = pending[0]
        const operation = await requestApi<{ status: 'UNKNOWN' | 'FAILED' | 'SUCCESS' }>(backendUrl,
          `${path}/info-sections/${job.section}/media-operations/${job.request}`, { signal: controller.signal }, deps)
        if (disposed) return
        outcome = operation.ok ? operation.data.status : 'UNKNOWN'
        if (outcome === 'UNKNOWN' && job.rejected) outcome = 'FAILED'
        job.progress.status = outcome
        if (outcome === 'SUCCESS') {
          markSuccess(job)
          pending.shift()
          if (pending.length) pending[0].revision = state.sections.find((s) => s.id === pending[0].section)?.revision ?? pending[0].revision
        }
        busy = false; uncertain = pending.length > 0; render()
        status.textContent = outcome === 'FAILED'
          ? 'Загрузка окончательно не выполнена. Прежнее вложение сохранено. Можно явно начать новую загрузку выбранного файла.'
          : outcome === 'SUCCESS' ? `${successText(job)}. Операция подтверждена. Повторная загрузка этого файла не требуется.${pending.length ? ' Продолжите загрузку оставшихся файлов.' : ''}`
          : 'Результат пока неизвестен. Проверьте позже или повторите исходную операцию. При перезагрузке приложения выбранные файлы и ключ повтора могут потеряться.'
        return
      }
      uncertain = false; render(); status.textContent = state.enabled ? 'Изменения материалов сохраняются сразу. Черновик текста карточки сохраняется отдельно.' : 'Прямая загрузка временно отключена. Для добавления используйте инструкцию Telegram-бота ниже.' }
    else { render(); status.textContent = result.error.message || 'Не удалось прочитать материалы. Обновите список.' }
  }
  const upload = async () => {
    if (busy || disposed || !state?.enabled || !pending.length || outcome === 'FAILED') return
    busy = true; uncertain = false; render()
    while (pending.length && !disposed) {
      const job = pending[0]
      job.progress.status = 'UPLOADING'
      render()
      status.textContent = `Загружаем файл ${job.progress.index} из ${job.progress.total} (${job.progress.pdf ? 'PDF' : 'изображение'}). Ожидаем подтверждение сервера. Старое вложение сохранится до успешной замены.`
      job.submitted = true
      const result = await requestApi<VenueInfoMediaState>(backendUrl, `${path}/info-sections/${job.section}/media`, {
        method: 'POST', signal: controller.signal, body: job.file,
        headers: { 'Content-Type': job.file.type || 'application/octet-stream', 'Idempotency-Key': job.request, 'X-Media-Revision': String(job.revision), ...(job.replace ? { 'X-Replaces-Media': job.replace } : {}) }
      }, deps)
      if (disposed) return
      if (!result.ok) {
        busy = false; uncertain = true
        job.rejected = result.error.status === 400 || result.error.status === 413
        outcome = job.rejected ? 'FAILED' : 'UNKNOWN'; job.progress.status = outcome; render()
        status.textContent = outcome === 'FAILED'
          ? `${result.error.message || 'Файл отклонён.'} Загрузка не выполнена, прежнее вложение сохранено. Проверьте список и явно начните новую загрузку подходящего файла.`
          : `${result.error.message || 'Ответ не получен.'} Проверьте список. Повтор отправит тот же файл с тем же ключом операции; новая копия не создаётся. При перезагрузке приложения выбранный файл может потеряться.`
        return
      }
      state = result.data; outcome = 'SUCCESS'; markSuccess(job); pending.shift(); render()
      if (pending.length) pending[0].revision = state.sections.find((s) => s.id === pending[0].section)?.revision ?? pending[0].revision
    }
    busy = false; render()
    const saved = progress.filter((item) => item.status === 'SUCCESS')
    status.textContent = `${saved.length === 1 ? (saved[0].pdf ? 'PDF загружен' : 'Изображение загружено') : `Загружено файлов: ${saved.length} из ${progress.length}`}. Сохранённые материалы показаны ниже в своих разделах.`
  }
  const select = (section: Section, files: File[], replace?: string) => {
    if (busy || uncertain || pending.length) return
    if (!files.length) return
    if (files.some((f) => (!['image/jpeg', 'image/png', 'application/pdf'].includes(f.type) && !(['', 'application/octet-stream'].includes(f.type) && /\.(jpe?g|png|pdf)$/i.test(f.name))) || f.size === 0 || f.size > 10 * 1024 * 1024)) {
      status.textContent = 'Выберите JPEG, PNG или PDF до 10 МБ. HEIC/HEIF экспортируйте в JPEG.'; return
    }
    if (replace ? files.length !== 1 : section.media.length + files.length > 10) { status.textContent = 'В разделе максимум 10 вложений. Для замены выберите один файл.'; return }
    progress = files.map((file, index) => ({ index: index + 1, total: files.length, section: section.id, pdf: file.type === 'application/pdf' || /\.pdf$/i.test(file.name), status: 'WAITING' }))
    pending = files.map((file, index) => ({ progress: progress[index], file, section: section.id, revision: section.revision, request: crypto.randomUUID(), replace }))
    outcome = 'UNKNOWN'; void upload()
  }
  const action = async (section: Section, kind: string, media?: Media) => {
    if (busy || uncertain || pending.length) return
    if (kind === 'delete') {
      const cleanup = media?.source === 'OBJECT_STORAGE'
        ? 'Удаление из хранилища запланировано после 7 дней и выполняется с повторными попытками. Для восстановления потребуется новая загрузка.'
        : 'Удалится только ссылка в платформе; физическое удаление Telegram-файла не поддерживается.'
      if (!window.confirm(`Удалить вложение? Новые открытия карточки, бота и предпросмотра больше не покажут этот файл. Уже полученные копии не отзываются. ${cleanup}`)) return
    }
    busy = true; render(); status.textContent = 'Сохраняем…'
    const result = await requestApi<VenueInfoMediaState>(backendUrl, `${path}/info-sections/${section.id}/media-actions`, {
      method: 'POST', signal: controller.signal, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ revision: section.revision, action: kind, mediaId: media?.id ?? null })
    }, deps)
    if (disposed) return
    busy = false
    if (result.ok) { state = result.data; render(); status.textContent = 'Изменение сохранено.' }
    else { uncertain = true; render(); status.textContent = `${result.error.message || 'Ответ не получен.'} Обновите список перед следующим действием.` }
  }
  const picker = (section: Section, media?: Media) => {
    const input = el('input') as HTMLInputElement
    input.type = 'file'; input.accept = 'image/jpeg,image/png,application/pdf,.jpg,.jpeg,.png,.pdf'; input.multiple = !media
    // A replacement gets a new media ID but keeps the same attachment slot.
    input.dataset.mediaFocus = `picker:${section.id}:${media?.sortOrder ?? 'add'}`
    input.disabled = busy || uncertain || pending.length > 0 || !state?.enabled || section.media.some((m) => m.status === 'UPLOADING')
    input.setAttribute('aria-label', media ? `Заменить вложение ${media.sortOrder + 1}` : `Добавить файлы: ${section.title}`)
    input.addEventListener('change', () => { select(section, Array.from(input.files ?? []), media?.id); input.value = '' })
    const label = el('label', { className: 'venue-info-file-label' })
    append(label, el('span', { text: media ? 'Заменить это вложение' : 'Добавить JPEG, PNG или PDF' }), input)
    return label
  }
  const renderContents = () => {
    controls.replaceChildren()
    append(controls, button('Обновить список', () => void refresh(), busy), ...(pending.length ? [button(outcome === 'FAILED' ? 'Начать новую загрузку' : 'Повторить загрузку', () => {
      if (outcome === 'FAILED' && state) {
        const job = pending[0]
        const section = state.sections.find((s) => s.id === job.section)
        if (!section) return
        pending[0] = { ...job, request: crypto.randomUUID(), revision: section.revision, submitted: false, rejected: false }
        outcome = 'UNKNOWN'
      }
      void upload()
    }, busy), button('Отменить оставшиеся файлы', () => {
      if (outcome === 'UNKNOWN' && pending[0]?.submitted) {
        pending.slice(1).forEach((job) => { job.progress.status = 'CANCELLED' }); pending = pending.slice(0, 1); render()
        status.textContent = 'Неотправленные файлы отменены. Исход неопределённой операции нужно проверить или безопасно повторить.'
      } else { pending.forEach((job) => { job.progress.status = 'CANCELLED' }); pending = []; uncertain = false; void refresh() }
    }, busy)] : []))
    if (!state) return
    for (const [id, view] of sections) {
      if (state.sections.some((section) => section.id === id)) continue
      view.items.forEach((item) => item.preview?.dispose()); view.element.remove(); sections.delete(id)
    }
    for (const section of state.sections) {
      let view = sections.get(section.id)
      if (!view) {
        view = { element: el('section', { className: 'venue-info-section' }), title: el('h5'), visibility: el('p'), actions: el('div'), progress: el('div', { className: 'venue-info-upload-progress' }), attachments: el('div'), items: new Map() }
        append(view.element, view.title, view.visibility, view.actions, view.progress, view.attachments)
        sections.set(section.id, view); content.appendChild(view.element)
      }
      view.title.textContent = section.type === 'menu' ? '📖 Фото-меню' : section.title
      view.visibility.textContent = section.visible ? 'Раздел показывается в карточке.' : 'Раздел скрыт от гостей и предпросмотра.'
      view.actions.replaceChildren(button(section.visible ? 'Скрыть раздел' : 'Показать раздел', () => void action(section, section.visible ? 'hide' : 'show'), undefined, `section:${section.id}:visibility`), picker(section))
      view.progress.replaceChildren()
      for (const item of progress.filter((item) => item.section === section.id)) {
        const row = el('div', { className: 'venue-info-upload-file' })
        row.dataset.state = item.status.toLowerCase()
        const message = {
          WAITING: 'Ожидает загрузки', UPLOADING: 'Загружается · ожидаем подтверждение сервера',
          SUCCESS: item.pdf ? 'PDF загружен' : 'Изображение загружено',
          UNKNOWN: 'Результат неизвестен · проверьте список или повторите исходную операцию',
          FAILED: 'Загрузка не выполнена', CANCELLED: 'Загрузка отменена'
        }[item.status]
        append(row, el('p', { text: `Файл ${item.index} из ${item.total} · ${item.pdf ? 'PDF' : 'Изображение'} · ${message}` }))
        if (item.status === 'UPLOADING') {
          const indicator = el('progress')
          indicator.setAttribute('aria-label', `Загрузка файла ${item.index} из ${item.total}`)
          row.appendChild(indicator)
        }
        view.progress.appendChild(row)
      }
      if (section.media.some((m) => m.status === 'UPLOADING')) view.progress.appendChild(el('p', { text: 'В разделе ещё выполняется загрузка. Обновите список позже; незавершённая операция очищается после 10 минут при работающем сервисе очистки.' }))
      for (const [id, item] of view.items) {
        if (section.media.some((media) => media.id === id)) continue
        item.preview?.dispose(); item.element.remove(); view.items.delete(id)
      }
      const ordered = [...section.media].sort((a, b) => a.sortOrder - b.sortOrder)
      ordered.forEach((media, index) => {
        let item = view!.items.get(media.id)
        if (!item) {
          item = { element: el('div', { className: 'venue-info-attachment' }), label: el('p', { className: 'info-media-title' }), actions: el('div'), preview: null }
          item.element.dataset.mediaId = media.id
          append(item.element, item.label, item.actions)
          view!.items.set(media.id, item)
        }
        item.label.textContent = `${media.type === 'pdf' ? 'PDF' : 'Изображение'} ${media.sortOrder + 1} · ${media.status === 'READY' ? 'Сохранено' : media.status === 'UPLOADING' ? 'Загружается' : 'Недоступно'} · ${media.visible ? 'Видимое' : 'Скрытое'} · ${media.source === 'TELEGRAM_FILE' ? 'Telegram' : 'Хранилище'}`
        if (media.status === 'READY' && !item.preview) {
          item.preview = createInfoMediaPreview(media.type === 'pdf' ? 'pdf' : 'image', `${section.title} ${media.sortOrder + 1}`, (signal) =>
            loadInfoMediaContent(backendUrl, `${path}/info-sections/${section.id}/media/${encodeURIComponent(media.id)}/content`, signal, true))
          item.element.insertBefore(item.preview.element, item.actions)
        } else if (media.status !== 'READY' && item.preview) {
          item.preview.dispose(); item.preview.element.remove(); item.preview = null
        }
        item.actions.replaceChildren()
        if (media.status === 'READY') append(item.actions, picker(section, media), button(media.visible ? 'Скрыть вложение' : 'Показать вложение', () => void action(section, media.visible ? 'hide' : 'show', media), undefined, `media:${media.id}:visibility`), button('Удалить вложение', () => void action(section, 'delete', media), undefined, `media:${media.id}:delete`))
        if (view!.attachments.children[index] !== item.element) view!.attachments.insertBefore(item.element, view!.attachments.children[index] ?? null)
      })
    }
  }
  const render = () => {
    const focused = document.activeElement
    if (focused instanceof HTMLElement && element.contains(focused) && focused.dataset.mediaFocus) {
      focusTarget = focused.dataset.mediaFocus
    }
    updatingDom = true
    try { renderContents() } finally { updatingDom = false }
    if (busy) return
    const key = focusTarget
    focusTarget = null
    if (!key || disposed || document.activeElement !== document.body) return
    const target = Array.from(element.querySelectorAll<HTMLElement>('[data-media-focus]')).find((node) => node.dataset.mediaFocus === key)
    if (target && !target.matches(':disabled') && target.getClientRects().length && getComputedStyle(target).visibility !== 'hidden') {
      target.focus({ preventScroll: true })
    }
  }
  element.addEventListener('toggle', () => { if ((element as HTMLDetailsElement).open && !state) void refresh() })
  return { element, busy: () => busy, pending: () => pending.length > 0, dispose: () => { disposed = true; controller.abort(); pending = []; clearPreviews() } }
}
