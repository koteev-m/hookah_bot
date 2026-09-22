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

export const tableTokenQueryParamKeys = ['table_token', 'tableToken', 'tgWebAppStartParam', 'startapp', 'start_param'] as const

export function extractTableTokenFromScannedText(
  scannedText: string,
  context: { miniAppUrl: string; botUsername: string | null }
): string | null {
  const text = scannedText.trim()
  // TableTokenGenerator emits unpadded base64url. A URL or arbitrary text is never a raw token.
  const normalizeQrToken = (value: string | null) =>
    value && /^[A-Za-z0-9_-]+$/.test(value) ? normalizeTableToken(value) : null
  if (/^[A-Za-z0-9_-]+$/.test(text)) {
    return normalizeQrToken(text)
  }
  try {
    const url = new URL(text)
    const miniAppUrl = new URL(context.miniAppUrl)
    if (url.username || url.password || !['https:', 'http:'].includes(url.protocol)) return null
    const isTelegram = url.protocol === 'https:' && url.hostname === 't.me' && !url.port
    const isMiniApp = url.origin === miniAppUrl.origin && url.pathname === miniAppUrl.pathname
    if (!isTelegram && !isMiniApp) return null
    if (isTelegram) {
      const username = url.pathname.slice(1)
      if (!/^[A-Za-z0-9_]+bot$/i.test(username) || url.hash) return null
      if (context.botUsername && username.toLowerCase() !== context.botUsername.replace(/^@/, '').toLowerCase()) return null
      let unsupportedParam = false
      url.searchParams.forEach((_value, key) => {
        if (key !== 'start' && key !== 'startapp') unsupportedParam = true
      })
      if (unsupportedParam) return null
    }
    const keys = isTelegram ? ['start', 'startapp'] : [...tableTokenQueryParamKeys, 'start']
    const candidates = keys.flatMap((key) => url.searchParams.getAll(key))
    // Even repeated identical parameters are ambiguous, and URLSearchParams decodes exactly once.
    if (candidates.length !== 1 || (!isTelegram && url.searchParams.has('start'))) return null
    return normalizeQrToken(candidates[0])
  } catch {
    return null
  }
}
