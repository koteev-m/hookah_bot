export const REQUEST_ABORTED_CODE = 'REQUEST_ABORTED'

export function isAbortError(error: unknown): error is DOMException {
  return error instanceof DOMException && error.name === 'AbortError'
}
