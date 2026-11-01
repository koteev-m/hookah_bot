import { AnnotationMode, getDocument, PDFWorker, type RenderTask } from 'pdfjs-dist/legacy/build/pdf.mjs'
import workerUrl from 'pdfjs-dist/legacy/build/pdf.worker.min.mjs?url'

// Vite emits only local assets. The maps are lazy URLs, not imported font/decoder bytes.
const resources = import.meta.glob('/node_modules/pdfjs-dist/{cmaps,standard_fonts,wasm}/*', {
  query: '?url', import: 'default', eager: true
}) as Record<string, string>

const MAX_PIXELS = 1_500_000
const MAX_SIDE = 2048

// Public ownerDocument context: PDF.js destroy waits for the worker before removing
// fonts/filters. Keep ownership locally as well, so a dead worker cannot retain them.
function createDocumentResources() {
  const fonts = new Set<FontFace>()
  const documentFonts = document.fonts as FontFaceSet & Set<FontFace>
  const filters = document.createElement('div')
  let closed = false
  const scopedFonts = {
    add(font: FontFace) {
      if (!closed) { fonts.add(font); documentFonts.add(font) }
      return scopedFonts
    },
    delete(font: FontFace) { return fonts.delete(font) && documentFonts.delete(font) }
  }
  const ownerDocument = new Proxy(document, {
    get(target, key) {
      if (key === 'fonts') return scopedFonts
      if (key === 'body') {
        if (!closed && !filters.isConnected) target.body.appendChild(filters)
        return filters
      }
      const value = Reflect.get(target, key, target)
      return typeof value === 'function' ? value.bind(target) : value
    }
  })
  return { ownerDocument, clear() {
    if (closed) return
    closed = true
    for (const font of fonts) documentFonts.delete(font)
    fonts.clear()
    filters.remove()
    filters.replaceChildren()
  } }
}

// PDF.js also creates temporary canvases for masks/patterns. Bound those allocations too.
class LimitedCanvasFactory {
  private canvases = new Set<HTMLCanvasElement>()
  private pixels = 0

  create(width: number, height: number) {
    const canvas = document.createElement('canvas')
    this.canvases.add(canvas)
    canvas.width = canvas.height = 0
    const target = { canvas, context: canvas.getContext('2d')! }
    this.reset(target, width, height)
    return target
  }

  reset(target: { canvas: HTMLCanvasElement }, width: number, height: number) {
    const pixels = Math.ceil(width) * Math.ceil(height)
    const total = this.pixels - target.canvas.width * target.canvas.height + pixels
    if (!Number.isFinite(pixels) || width <= 0 || height <= 0 || width > 8192 || height > 8192 || total > 8_000_000) {
      throw new Error('PDF canvas limit')
    }
    this.pixels = total
    target.canvas.width = Math.ceil(width)
    target.canvas.height = Math.ceil(height)
  }

  destroy(target: { canvas: HTMLCanvasElement | null; context: CanvasRenderingContext2D | null }) {
    if (!target.canvas) return
    this.pixels -= target.canvas.width * target.canvas.height
    this.canvases.delete(target.canvas)
    target.canvas.width = target.canvas.height = 0
    target.canvas = null
    target.context = null
  }

  clear() {
    for (const canvas of this.canvases) canvas.width = canvas.height = 0
    this.canvases.clear()
    this.pixels = 0
  }
}

export async function renderPdfPage(data: Uint8Array, canvas: HTMLCanvasElement, pageNumber: number, signal: AbortSignal) {
  signal.throwIfAborted()
  // An explicit port prevents PDF.js falling back to parsing on the main thread.
  const port = new Worker(workerUrl, { type: 'module', name: 'venue-info-pdf' })
  const documentResources = createDocumentResources()
  const resourceAbort = new AbortController()
  let worker: PDFWorker | undefined
  let loading: ReturnType<typeof getDocument> | undefined
  const factories: LimitedCanvasFactory[] = []
  class CanvasFactory extends LimitedCanvasFactory {
    constructor() { super(); factories.push(this) }
  }
  const resource = async (folder: string, name: string) => {
    const url = resources[`/node_modules/pdfjs-dist/${folder}/${name}`]
    if (!url) throw new Error('PDF resource unavailable')
    const response = await fetch(url, { signal: resourceAbort.signal, credentials: 'same-origin' })
    if (!response.ok) throw new Error('PDF resource unavailable')
    return new Uint8Array(await response.arrayBuffer())
  }
  let render: RenderTask | undefined
  let rejectAbort: (error: unknown) => void = () => undefined
  const interrupted = new Promise<never>((_, reject) => { rejectAbort = reject })
  let destruction: Promise<void> | undefined
  const destroy = () => {
    render?.cancel()
    destruction ??= loading?.destroy().catch(() => undefined) ?? Promise.resolve()
  }
  const abort = () => { destroy(); rejectAbort(new DOMException('Aborted', 'AbortError')) }
  const workerError = () => { destroy(); rejectAbort(new Error('PDF worker unavailable')) }
  signal.addEventListener('abort', abort, { once: true })
  port.addEventListener('error', workerError)
  port.addEventListener('messageerror', workerError)
  try {
    worker = PDFWorker.create({ port })
    loading = getDocument({
      ownerDocument: documentResources.ownerDocument,
      data, worker, isEvalSupported: false, enableXfa: false, stopAtErrors: true,
      maxImageSize: 8_000_000, canvasMaxAreaInBytes: 8_000_000,
      isOffscreenCanvasSupported: false, isImageDecoderSupported: false,
      useWorkerFetch: false, useSystemFonts: false, CanvasFactory, verbosity: 0,
      CMapReaderFactory: class {
        async fetch({ name }: { name: string }) { return { cMapData: await resource('cmaps', `${name}.bcmap`), compressionType: 1 } }
      },
      StandardFontDataFactory: class {
        fetch({ filename }: { filename: string }) { return resource('standard_fonts', filename) }
      },
      WasmFactory: class {
        fetch({ filename }: { filename: string }) { return resource('wasm', filename) }
      }
    })
    const document = await Promise.race([loading.promise, interrupted])
    signal.throwIfAborted()
    if (document.numPages < 1 || document.numPages > 200) throw new Error('PDF page limit')
    const page = await Promise.race([document.getPage(pageNumber), interrupted])
    const natural = page.getViewport({ scale: 1 })
    const scale = Math.min(2, MAX_SIDE / natural.width, MAX_SIDE / natural.height,
      Math.sqrt(MAX_PIXELS / (natural.width * natural.height)))
    if (!Number.isFinite(scale) || scale <= 0) throw new Error('Invalid PDF page')
    const viewport = page.getViewport({ scale })
    canvas.width = Math.max(1, Math.floor(viewport.width))
    canvas.height = Math.max(1, Math.floor(viewport.height))
    render = page.render({ canvas, viewport, annotationMode: AnnotationMode.DISABLE, background: '#ffffff' })
    await Promise.race([render.promise, interrupted])
    signal.throwIfAborted()
    page.cleanup()
    return document.numPages
  } finally {
    signal.removeEventListener('abort', abort)
    port.removeEventListener('error', workerError)
    port.removeEventListener('messageerror', workerError)
    destroy()
    // Allow graceful destroy, but never wait indefinitely for a dead worker.
    let cleanupTimer: number | undefined
    await Promise.race([destruction, new Promise<void>((resolve) => { cleanupTimer = window.setTimeout(resolve, 250) })])
    window.clearTimeout(cleanupTimer)
    worker?.destroy()
    port.terminate()
    resourceAbort.abort()
    documentResources.clear()
    factories.forEach((factory) => factory.clear())
  }
}
