const MIN_ASCII = 0x21
const MAX_ASCII = 0x7e
const MAX_TABLE_TOKEN_LENGTH = 128

type TableTokenValidationResult =
  | { ok: true; value: string }
  | { ok: false }

export function validateTableToken(value: string | null | undefined): TableTokenValidationResult {
  if (value === null || value === undefined) {
    return { ok: false }
  }
  const trimmed = value.trim()
  if (!trimmed || trimmed.length > MAX_TABLE_TOKEN_LENGTH) {
    return { ok: false }
  }
  for (let index = 0; index < trimmed.length; index += 1) {
    const code = trimmed.charCodeAt(index)
    if (code < MIN_ASCII || code > MAX_ASCII) {
      return { ok: false }
    }
  }
  return { ok: true, value: trimmed }
}

export function normalizeTableToken(value: string | null | undefined): string | null {
  const result = validateTableToken(value)
  return result.ok ? result.value : null
}
