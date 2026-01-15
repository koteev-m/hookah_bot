export const REQUEST_ABORTED_CODE = 'REQUEST_ABORTED'

export function isAbortError(error: unknown): boolean {
  return (
    (error instanceof DOMException && error.name === 'AbortError') ||
    (typeof error === 'object' &&
      error !== null &&
      (error as { name?: string }).name === 'AbortError')
  )
}
