import { clearSession, getAccessToken } from './auth'

export async function loadInfoMediaContent(backendUrl: string, path: string, signal: AbortSignal, authenticated: boolean) {
  const headers = new Headers()
  if (authenticated) {
    const session = await getAccessToken()
    signal.throwIfAborted()
    if (!session.ok) throw new Error('Media access unavailable')
    headers.set('Authorization', `Bearer ${session.data.token}`)
  }
  const response = await fetch(`${backendUrl}${path}`, { signal, headers, cache: 'no-store', redirect: 'error' })
  if (authenticated && response.status === 401) clearSession()
  const type = response.headers.get('Content-Type')?.split(';')[0].trim().toLowerCase() ?? ''
  const limit = 10 * 1024 * 1024
  if (!response.ok || !['application/pdf', 'image/jpeg', 'image/png'].includes(type) || Number(response.headers.get('Content-Length')) > limit) {
    await response.body?.cancel()
    throw new Error('Media unavailable')
  }
  const reader = response.body?.getReader()
  if (!reader) throw new Error('Media unavailable')
  let size = 0
  const chunks: Uint8Array<ArrayBuffer>[] = []
  try {
    while (true) {
      signal.throwIfAborted()
      const { done, value } = await reader.read()
      if (done) break
      size += value.byteLength
      if (size > limit) throw new Error('Media size limit')
      chunks.push(value)
    }
    if (!size) throw new Error('Empty media')
    return new Blob(chunks, { type })
  } finally {
    await reader.cancel().catch(() => undefined)
    reader.releaseLock()
  }
}
