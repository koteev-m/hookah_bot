export function isDebugEnabled(): boolean {
  if (!import.meta.env.DEV) {
    return false
  }
  if (typeof window === 'undefined') {
    return false
  }
  const params = new URLSearchParams(window.location.search)
  const value = params.get('debug')
  return value === '1' || value === 'true'
}
