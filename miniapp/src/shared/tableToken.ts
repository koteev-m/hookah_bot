const MIN_ASCII = 0x21
const MAX_ASCII = 0x7e
const MAX_TABLE_TOKEN_LENGTH = 128

export function normalizeTableToken(value: string | null | undefined): string | null {
  if (value === null || value === undefined) {
    return null
  }
  const trimmed = value.trim()
  if (!trimmed || trimmed.length > MAX_TABLE_TOKEN_LENGTH) {
    return null
  }
  for (let index = 0; index < trimmed.length; index += 1) {
    const code = trimmed.charCodeAt(index)
    if (code < MIN_ASCII || code > MAX_ASCII) {
      return null
    }
  }
  return trimmed
}
