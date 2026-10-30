import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi, requestBinary } from '../shared/api/request'
import { append, el } from '../shared/ui/dom'

type Media = { id: string; type: string; visible: boolean; status: string; sortOrder: number; size: number | null; source: string }
type Section = { id: number; title: string; type: string; visible: boolean; revision: number; media: Media[] }
export type VenueInfoMediaState = { enabled: boolean; sections: Section[] }
type Upload = { file: File; section: number; revision: number; request: string; replace?: string; submitted?: boolean; rejected?: boolean }

export function createVenueInfoMediaEditor(backendUrl: string, isDebug: boolean, venueId: number) {
  const element = el('details', { className: 'venue-info-editor' })
  const content = el('div')
  const status = el('p', { text: '' }); status.setAttribute('role', 'status'); status.setAttribute('aria-live', 'polite')
  append(element, el('summary', { text: 'Управлять фотографиями и PDF' }), status, content)
  const deps = { isDebug, getAccessToken, clearSession }
  const path = `/api/venue/${venueId}`
  let state: VenueInfoMediaState | null = null
  let busy = false; let disposed = false; let uncertain = false
  let pending: Upload[] = []
  let outcome: 'UNKNOWN' | 'FAILED' | 'SUCCESS' = 'UNKNOWN'
  const urls = new Set<string>()
  const controller = new AbortController()
  const button = (text: string, action: () => void, disabled = busy || uncertain || pending.length > 0) => {
    const b = el('button', { className: 'button-small button-secondary', text }) as HTMLButtonElement
    b.type = 'button'; b.disabled = disabled; b.addEventListener('click', action); return b
  }
  const revoke = () => { urls.forEach((url) => URL.revokeObjectURL(url)); urls.clear() }
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
        if (outcome === 'SUCCESS') {
          pending.shift()
          if (pending.length) pending[0].revision = state.sections.find((s) => s.id === pending[0].section)?.revision ?? pending[0].revision
        }
        busy = false; uncertain = pending.length > 0; render()
        status.textContent = outcome === 'FAILED'
          ? 'Загрузка окончательно не выполнена. Прежнее вложение сохранено. Можно явно начать новую загрузку выбранного файла.'
          : outcome === 'SUCCESS' ? 'Операция подтверждена. Повторная загрузка этого файла не требуется.'
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
      status.textContent = `Загружаем файл; осталось ${pending.length}. Старое вложение сохранится до успешной замены.`
      job.submitted = true
      const result = await requestApi<VenueInfoMediaState>(backendUrl, `${path}/info-sections/${job.section}/media`, {
        method: 'POST', signal: controller.signal, body: job.file,
        headers: { 'Content-Type': job.file.type || 'application/octet-stream', 'Idempotency-Key': job.request, 'X-Media-Revision': String(job.revision), ...(job.replace ? { 'X-Replaces-Media': job.replace } : {}) }
      }, deps)
      if (disposed) return
      if (!result.ok) {
        busy = false; uncertain = true
        job.rejected = result.error.status === 400 || result.error.status === 413
        outcome = job.rejected ? 'FAILED' : 'UNKNOWN'; render()
        status.textContent = outcome === 'FAILED'
          ? `${result.error.message || 'Файл отклонён.'} Загрузка не выполнена, прежнее вложение сохранено. Проверьте список и явно начните новую загрузку подходящего файла.`
          : `${result.error.message || 'Ответ не получен.'} Проверьте список. Повтор отправит тот же файл с тем же ключом операции; новая копия не создаётся. При перезагрузке приложения выбранный файл может потеряться.`
        return
      }
      state = result.data; outcome = 'SUCCESS'; pending.shift()
      if (pending.length) pending[0].revision = state.sections.find((s) => s.id === pending[0].section)?.revision ?? pending[0].revision
    }
    busy = false; render(); status.textContent = 'Материалы сохранены. Откройте «Предпросмотр для гостя», чтобы проверить видимые вложения.'
  }
  const select = (section: Section, files: File[], replace?: string) => {
    if (busy || uncertain || pending.length) return
    if (!files.length) return
    if (files.some((f) => (!['image/jpeg', 'image/png', 'application/pdf'].includes(f.type) && !(['', 'application/octet-stream'].includes(f.type) && /\.(jpe?g|png|pdf)$/i.test(f.name))) || f.size === 0 || f.size > 10 * 1024 * 1024)) {
      status.textContent = 'Выберите JPEG, PNG или PDF до 10 МБ. HEIC/HEIF экспортируйте в JPEG.'; return
    }
    if (replace ? files.length !== 1 : section.media.length + files.length > 10) { status.textContent = 'В разделе максимум 10 вложений. Для замены выберите один файл.'; return }
    pending = files.map((file) => ({ file, section: section.id, revision: section.revision, request: crypto.randomUUID(), replace }))
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
    input.disabled = busy || uncertain || pending.length > 0 || !state?.enabled || section.media.some((m) => m.status === 'UPLOADING')
    input.setAttribute('aria-label', media ? `Заменить вложение ${media.sortOrder + 1}` : `Добавить файлы: ${section.title}`)
    input.addEventListener('change', () => { select(section, Array.from(input.files ?? []), media?.id); input.value = '' })
    const label = el('label', { className: 'venue-info-file-label' })
    append(label, el('span', { text: media ? 'Заменить это вложение' : 'Добавить JPEG, PNG или PDF' }), input)
    return label
  }
  const open = async (section: Section, media: Media) => {
    status.textContent = 'Готовим файл…'
    const result = await requestBinary(backendUrl, `${path}/info-sections/${section.id}/media/${encodeURIComponent(media.id)}/content`, { signal: controller.signal }, deps)
    if (disposed) return
    if (!result.ok) { status.textContent = result.error.message || 'Файл недоступен.'; return }
    const url = URL.createObjectURL(result.data.blob); urls.add(url)
    const link = el('a', { text: 'Открыть сохранённый файл' }) as HTMLAnchorElement
    link.href = url; link.target = '_blank'; link.rel = 'noopener noreferrer'
    status.replaceChildren(link)
  }
  const render = () => {
    revoke(); content.replaceChildren()
    append(content, button('Обновить список', () => void refresh(), busy), ...(pending.length ? [button(outcome === 'FAILED' ? 'Начать новую загрузку' : 'Повторить загрузку', () => {
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
        pending = pending.slice(0, 1); render()
        status.textContent = 'Неотправленные файлы отменены. Исход неопределённой операции нужно проверить или безопасно повторить.'
      } else { pending = []; uncertain = false; void refresh() }
    }, busy)] : []))
    if (!state) return
    for (const section of state.sections) {
      const row = el('section', { className: 'venue-info-section' })
      append(row, el('h5', { text: section.type === 'menu' ? '📖 Фото-меню' : section.title }), el('p', { text: section.visible ? 'Раздел показывается в карточке.' : 'Раздел скрыт от гостей и предпросмотра.' }), button(section.visible ? 'Скрыть раздел' : 'Показать раздел', () => void action(section, section.visible ? 'hide' : 'show')), picker(section))
      if (section.media.some((m) => m.status === 'UPLOADING')) row.appendChild(el('p', { text: 'В разделе ещё выполняется загрузка. Обновите список позже; незавершённая операция очищается после 10 минут при работающем сервисе очистки.' }))
      for (const media of section.media) {
        const item = el('div', { className: 'venue-info-attachment' })
        append(item, el('p', { text: `${media.type === 'pdf' ? 'PDF' : 'Изображение'} ${media.sortOrder + 1} · ${media.status === 'UPLOADING' ? 'Загружается' : media.visible ? 'Видимое' : 'Скрытое'} · ${media.source === 'TELEGRAM_FILE' ? 'Telegram' : 'Хранилище'}` }))
        if (media.status === 'READY') append(item, button('Просмотреть', () => void open(section, media)), picker(section, media), button(media.visible ? 'Скрыть вложение' : 'Показать вложение', () => void action(section, media.visible ? 'hide' : 'show', media)), button('Удалить вложение', () => void action(section, 'delete', media)))
        row.appendChild(item)
      }
      content.appendChild(row)
    }
  }
  element.addEventListener('toggle', () => { if ((element as HTMLDetailsElement).open && !state) void refresh() })
  return { element, busy: () => busy, pending: () => pending.length > 0, dispose: () => { disposed = true; controller.abort(); pending = []; revoke() } }
}
