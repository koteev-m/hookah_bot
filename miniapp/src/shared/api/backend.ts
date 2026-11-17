export function getBackendBaseUrl(): string {
  const page = new URL(window.location.href)
  if (page.protocol !== 'https:' && !(page.protocol === 'http:' && isLoopback(page.hostname))) {
    throw new Error('Mini App requires HTTPS or a local development origin')
  }

  // Release assets always use their own origin, regardless of build env or launch parameters.
  if (import.meta.env.DEV) {
    const configured = import.meta.env.VITE_BACKEND_PUBLIC_URL?.trim()
    if (configured) {
      const backend = new URL(configured)
      if ((backend.protocol !== 'https:' && !(backend.protocol === 'http:' && isLoopback(backend.hostname))) ||
          backend.username || backend.password || backend.pathname !== '/' || backend.search || backend.hash) {
        throw new Error('VITE_BACKEND_PUBLIC_URL must be an HTTPS origin or a local HTTP origin')
      }
      return backend.origin
    }
    if (isLoopback(page.hostname)) return 'http://localhost:8080'
  }

  return page.origin
}

function isLoopback(hostname: string): boolean {
  return hostname === 'localhost' || hostname === '127.0.0.1' || hostname === '[::1]'
}
