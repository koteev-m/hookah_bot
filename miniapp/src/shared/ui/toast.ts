type ToastOptions = {
  durationMs?: number
}

let toastTimer: number | null = null
let toastNode: HTMLDivElement | null = null

function ensureToastNode(): HTMLDivElement {
  if (toastNode) {
    return toastNode
  }
  const node = document.createElement('div')
  node.className = 'toast'
  node.setAttribute('role', 'status')
  node.setAttribute('aria-live', 'polite')
  document.body.appendChild(node)
  toastNode = node
  return node
}

export function showToast(message: string, opts: ToastOptions = {}) {
  const durationMs = opts.durationMs ?? 2200
  const node = ensureToastNode()
  node.textContent = message
  node.dataset.visible = 'true'
  if (toastTimer) {
    window.clearTimeout(toastTimer)
  }
  toastTimer = window.setTimeout(() => {
    if (toastNode) {
      toastNode.dataset.visible = 'false'
    }
    toastTimer = null
  }, durationMs)
}
