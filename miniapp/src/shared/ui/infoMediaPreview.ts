import { append, el } from './dom'

export type InfoMediaLoader = (signal: AbortSignal) => Promise<Blob>

// One fetch/decode/render at a time, shared by Guest, Preview and the editor.
let queue: Promise<unknown> = Promise.resolve()
function abortable<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const abort = () => reject(new DOMException('Aborted', 'AbortError'))
    promise.then(resolve, reject).finally(() => signal.removeEventListener('abort', abort))
    if (signal.aborted) abort()
    else signal.addEventListener('abort', abort, { once: true })
  })
}
function serial<T>(signal: AbortSignal, action: () => Promise<T>): Promise<T> {
  const result = queue.then(() => { signal.throwIfAborted(); return action() })
  queue = result.catch(() => undefined)
  return result
}

export function createInfoMediaPreview(type: 'pdf' | 'image', label: string, load: InfoMediaLoader) {
  const element = el('div', { className: `info-media-preview venue-info-media-${type}` })
  element.setAttribute('aria-label', label)
  const surface = el('div', { className: 'info-media-surface' })
  const canvas = el('canvas')
  canvas.width = canvas.height = 0
  canvas.setAttribute('role', 'img')
  const image = el('img')
  image.alt = label
  image.decoding = 'async'
  const status = el('p', { className: 'status', text: 'Предпросмотр появится при прокрутке к файлу.' })
  status.setAttribute('aria-live', 'polite')
  const controls = el('div', { className: 'info-media-controls' })
  const previous = el('button', { className: 'button-small button-secondary', text: 'Назад' })
  const next = el('button', { className: 'button-small button-secondary', text: 'Далее' })
  previous.setAttribute('aria-label', 'Предыдущая страница')
  next.setAttribute('aria-label', 'Следующая страница')
  const counter = el('span')
  const retry = el('button', { className: 'button-small button-secondary', text: 'Повторить просмотр' })
  const download = el('button', { className: 'button-small button-secondary', text: 'Скачать файл' })
  for (const button of [previous, next, retry, download]) button.type = 'button'
  controls.hidden = type !== 'pdf'
  previous.disabled = next.disabled = true
  counter.textContent = '—'
  retry.hidden = download.hidden = true
  canvas.hidden = image.hidden = true
  append(controls, previous, counter, next)
  append(surface, type === 'pdf' ? canvas : image)
  append(element, surface, status, controls, retry, download)

  let page = 1; let pages = 0; let disposed = false; let visible = false; let ready = false
  let active: AbortController | null = null
  let imageUrl: string | null = null
  let downloadUrl: string | null = null
  let downloadTimer: number | undefined
  const revokeDownload = () => {
    window.clearTimeout(downloadTimer)
    if (downloadUrl) URL.revokeObjectURL(downloadUrl)
    downloadUrl = null
  }
  const clear = () => {
    ready = false
    canvas.width = canvas.height = 0
    canvas.hidden = image.hidden = true
    image.removeAttribute('src')
    if (imageUrl) URL.revokeObjectURL(imageUrl)
    imageUrl = null
  }
  const updateControls = (busy: boolean) => {
    controls.hidden = type !== 'pdf'
    previous.disabled = busy || page <= 1
    next.disabled = busy || page >= pages
    counter.textContent = pages ? `${page} из ${pages}` : '—'
  }
  const start = async () => {
    if (disposed || !visible || active || ready) return
    const controller = new AbortController()
    active = controller
    const { signal } = controller
    element.setAttribute('aria-busy', 'true')
    element.dataset.state = 'loading'
    retry.hidden = download.hidden = true
    status.textContent = type === 'pdf' ? `Загрузка страницы ${page}…` : 'Загрузка изображения…'
    updateControls(true)
    let received = false
    let timeout: number | undefined
    let timedOut = false
    try {
      await serial(signal, async () => {
        timeout = window.setTimeout(() => { timedOut = true; controller.abort() }, 20000)
        const blob = await abortable(load(signal), signal)
        signal.throwIfAborted()
        if (blob.size === 0 || blob.size > 10 * 1024 * 1024) throw new Error('Media size limit')
        received = true
        if (type === 'pdf') {
          const renderer = await abortable(import('./pdfPageRenderer'), signal)
          signal.throwIfAborted()
          pages = await renderer.renderPdfPage(new Uint8Array(await blob.arrayBuffer()), canvas, page, signal)
          signal.throwIfAborted()
          canvas.setAttribute('aria-label', `${label} · страница ${page} из ${pages}`)
          canvas.hidden = false
        } else {
          imageUrl = URL.createObjectURL(blob)
          image.src = imageUrl
          await abortable(image.decode(), signal)
          signal.throwIfAborted()
          image.hidden = false
        }
      })
      if (active !== controller || disposed) return
      ready = true
      element.dataset.state = 'ready'
      status.textContent = type === 'pdf' ? `Страница ${page} из ${pages}` : ''
    } catch {
      if (active !== controller || disposed || (signal.aborted && !timedOut)) return
      clear()
      element.dataset.state = 'error'
      status.textContent = received
        ? 'Не удалось показать файл. Повторите просмотр или скачайте файл для просмотра в другом приложении. На iPhone скачивание может открыть системный диалог.'
        : type === 'pdf'
          ? 'PDF недоступен: файл скрыт, удалён или не удалось получить ответ. Повторите просмотр. Доступные опубликованные материалы также можно запросить в Telegram-боте заведения.'
          : 'Не удалось загрузить изображение. Повторите просмотр. Доступный файл можно открыть в Telegram-боте.'
      retry.hidden = false
      download.hidden = !received
    } finally {
      window.clearTimeout(timeout)
      if (active === controller) {
        active = null
        element.setAttribute('aria-busy', 'false')
        updateControls(false)
      }
    }
  }
  const suspend = () => {
    active?.abort(); active = null
    clear(); revokeDownload()
    status.textContent = 'Предпросмотр появится при прокрутке к файлу.'
    retry.hidden = download.hidden = true
    updateControls(true)
    element.setAttribute('aria-busy', 'false')
    element.dataset.state = 'idle'
  }
  const turn = (delta: number) => {
    if (active || page + delta < 1 || page + delta > pages) return
    page += delta; clear(); void start()
  }
  previous.addEventListener('click', () => turn(-1))
  next.addEventListener('click', () => turn(1))
  retry.addEventListener('click', () => { clear(); void start() })
  download.addEventListener('click', async () => {
    if (active || disposed) return
    const controller = new AbortController()
    active = controller; download.disabled = true
    const timeout = window.setTimeout(() => controller.abort(), 15000)
    try {
      // Recheck authorization/visibility; never offer cached bytes after a denial.
      const blob = await serial(controller.signal, () => abortable(load(controller.signal), controller.signal))
      if (disposed || controller.signal.aborted || active !== controller) return
      if (!blob.size || blob.size > 10 * 1024 * 1024) throw new Error('Media size limit')
      revokeDownload()
      downloadUrl = URL.createObjectURL(blob)
      const link = el('a')
      link.href = downloadUrl; link.download = type === 'pdf' ? 'venue-document.pdf' : 'venue-image'
      link.rel = 'noopener noreferrer'
      link.click()
      downloadTimer = window.setTimeout(revokeDownload, 30000)
    } catch {
      if (!disposed && active === controller) {
        download.hidden = true
        status.textContent = 'Файл недоступен. Обновите список материалов и повторите просмотр.'
      }
    } finally {
      window.clearTimeout(timeout)
      if (active === controller) active = null
      download.disabled = false
    }
  })
  const observer = new IntersectionObserver(([entry]) => {
    visible = entry.isIntersecting && !document.hidden
    if (visible) void start()
    else suspend()
  }, { rootMargin: '250px' })
  observer.observe(element)
  const hide = () => { visible = false; suspend() }
  const resume = () => {
    if (document.hidden) { hide(); return }
    observer.unobserve(element); observer.observe(element)
  }
  window.addEventListener('pagehide', hide)
  window.addEventListener('pageshow', resume)
  document.addEventListener('visibilitychange', resume)
  return { element, dispose: () => {
    disposed = true; observer.disconnect(); suspend()
    window.removeEventListener('pagehide', hide)
    window.removeEventListener('pageshow', resume)
    document.removeEventListener('visibilitychange', resume)
  } }
}
