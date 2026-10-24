/** Accept only a username, never a URL or arbitrary contact text. */
export function telegramUsernameUrl(value: string | null | undefined): string | null {
  const username = value?.trim().replace(/^@/, '') ?? ''
  return /^[a-zA-Z][a-zA-Z0-9_]{4,31}$/.test(username) ? `https://t.me/${username}` : null
}
