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

export function createInfoMediaPreview(type: 'pdf' | 'image', label: string, load: InfoMediaLoader, mode?: 'thumbnail' | 'viewer') {
  const element = el('div', { className: `info-media-preview venue-info-media-${type}` })
  element.setAttribute('aria-label', label)
  if (mode) element.dataset.mode = mode
  const zoomOut = el('button', { className: 'button-secondary button-small', text: 'Уменьшить' })
  const zoomIn = el('button', { className: 'button-secondary button-small', text: 'Увеличить' })
  const fitWidth = el('button', { className: 'button-secondary button-small', text: 'По ширине' })
  const zoomControls = el('div', { className: 'info-media-controls' })
  append(zoomControls, zoomOut, fitWidth, zoomIn)
  const surface = el('div', { className: 'info-media-surface' })
  const canvas = el('canvas')
  canvas.width = canvas.height = 0
  canvas.setAttribute('role', 'img')
  const image = el('img')
  image.alt = label
  image.decoding = 'async'
  const status = el('p', { className: 'status', text: mode ? 'Загрузка файла…' : 'Предпросмотр появится при прокрутке к файлу.' })
  status.setAttribute('aria-live', 'polite')
  const controls = el('div', { className: 'info-media-controls' })
  const previous = el('button', { className: 'button-small button-secondary', text: 'Назад' })
  const next = el('button', { className: 'button-small button-secondary', text: 'Далее' })
  previous.setAttribute('aria-label', 'Предыдущая страница')
  next.setAttribute('aria-label', 'Следующая страница')
  const counter = el('span')
  const retry = el('button', { className: 'button-small button-secondary', text: 'Повторить просмотр' })
  const download = el('button', { className: 'button-small button-secondary', text: 'Скачать файл' })
  for (const button of [previous, next, retry, download, zoomOut, fitWidth, zoomIn]) button.type = 'button'
  controls.hidden = type !== 'pdf' || mode === 'thumbnail'
  previous.disabled = next.disabled = true
  counter.textContent = '—'
  retry.hidden = download.hidden = true
  canvas.hidden = image.hidden = true
  append(controls, previous, counter, next)
  append(surface, type === 'pdf' ? canvas : image)
  append(element, surface, status, controls, retry, download)
  if (mode === 'viewer') {
    const toolbar = el('div', { className: 'info-media-toolbar' })
    append(toolbar, controls, zoomControls, download)
    element.prepend(toolbar)
  }
  let zoom = 1
  const updateZoom = (value: number, anchorX = surface.clientWidth / 2,
    anchorY = Math.min(surface.clientHeight, window.innerHeight) / 2) => {
    const previousZoom = zoom
    zoom = Math.min(3, Math.max(1, Math.round(value * 100) / 100))
    const x = (surface.scrollLeft + anchorX) / previousZoom
    const y = (surface.scrollTop + anchorY) / previousZoom
    surface.style.setProperty('--guest-media-zoom', String(zoom))
    surface.classList.toggle('info-media-zoomed', zoom > 1)
    surface.scrollLeft = x * zoom - anchorX
    surface.scrollTop = y * zoom - anchorY
    zoomOut.disabled = zoom <= 1
    zoomIn.disabled = zoom >= 3
    fitWidth.setAttribute('aria-pressed', String(zoom === 1))
  }
  zoomOut.addEventListener('click', () => updateZoom(zoom - .5))
  zoomIn.addEventListener('click', () => updateZoom(zoom + .5))
  fitWidth.addEventListener('click', () => { updateZoom(1); surface.scrollTo(0, 0) })
  updateZoom(1)
  let pinch: { distance: number; zoom: number } | null = null
  const touchPair = (event: TouchEvent) => {
    if (event.touches.length !== 2) return null
    const [first, second] = Array.from(event.touches)
    const box = surface.getBoundingClientRect()
    return {
      distance: Math.hypot(first.clientX - second.clientX, first.clientY - second.clientY),
      x: (first.clientX + second.clientX) / 2 - box.left,
      y: (first.clientY + second.clientY) / 2 - box.top
    }
  }
  if (mode === 'viewer') {
    surface.addEventListener('touchstart', event => {
      const pair = touchPair(event)
      if (!pair || pair.distance === 0) return
      event.preventDefault()
      pinch = { distance: pair.distance, zoom }
    }, { passive: false })
    surface.addEventListener('touchmove', event => {
      const pair = touchPair(event)
      if (!pair || !pinch) return
      event.preventDefault()
      updateZoom(pinch.zoom * pair.distance / pinch.distance, pair.x, pair.y)
    }, { passive: false })
    const endPinch = () => { pinch = null }
    surface.addEventListener('touchend', endPinch)
    surface.addEventListener('touchcancel', endPinch)
  }

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
    controls.hidden = type !== 'pdf' || mode === 'thumbnail'
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
          const ratio = image.naturalWidth / image.naturalHeight
          element.dataset.orientation = ratio < .35 ? 'tall' : ratio < .9 ? 'portrait' : ratio > 1.1 ? 'landscape' : 'square'
          image.hidden = false
        }
      })
      if (active !== controller || disposed) return
      ready = true
      element.dataset.state = 'ready'
      status.textContent = type === 'pdf'
        ? mode === 'thumbnail' ? `Превью · ${pages} стр. Откройте документ для чтения.` : `Страница ${page} из ${pages}`
        : mode === 'thumbnail' && element.dataset.orientation === 'tall' ? 'Фрагмент длинного фото. Откройте, чтобы увидеть целиком.' : ''
      download.hidden = mode !== 'viewer'
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
    status.textContent = mode ? 'Загрузка файла…' : 'Предпросмотр появится при прокрутке к файлу.'
    retry.hidden = download.hidden = true
    updateControls(true)
    element.setAttribute('aria-busy', 'false')
    element.dataset.state = 'idle'
  }
  const turn = (delta: number) => {
    if (active || page + delta < 1 || page + delta > pages) return
    page += delta; clear(); surface.scrollTo(0, 0); void start()
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
