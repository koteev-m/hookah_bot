type ElementOptions = {
  className?: string
  id?: string
  text?: string
}

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  options?: ElementOptions
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag)
  if (options?.className) {
    node.className = options.className
  }
  if (options?.id) {
    node.id = options.id
  }
  if (options?.text !== undefined) {
    node.textContent = options.text
  }
  return node
}

export function append(parent: HTMLElement, ...children: Array<Node | null | undefined>) {
  children.forEach((child) => {
    if (child) {
      parent.appendChild(child)
    }
  })
  return parent
}

export function on<K extends keyof HTMLElementEventMap>(
  node: HTMLElement,
  eventName: K,
  handler: (event: HTMLElementEventMap[K]) => void
) {
  node.addEventListener(eventName, handler)
  return () => node.removeEventListener(eventName, handler)
}
