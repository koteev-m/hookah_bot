export function parsePositiveInt(value: string | null | undefined): number | null {
  if (value == null) {
    return null
  }
  const trimmed = value.trim()
  if (!trimmed || !/^\d+$/.test(trimmed)) {
    return null
  }
  const parsed = Number(trimmed)
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    return null
  }
  return parsed
}
