import { expect, test } from '@playwright/test'
import { telegramUsernameUrl } from '../src/shared/validation/telegramUsername'

test('Telegram username links accept only a complete username with an optional @', () => {
  for (const value of ['anna_owner', '@anna_owner', ' @Anna_owner1 ', 'A'.repeat(32)]) {
    expect(telegramUsernameUrl(value)).toBe(`https://t.me/${value.trim().replace(/^@/, '')}`)
  }
  for (const value of [
    null, undefined, '', 'abcd', 'A'.repeat(33), '12345', '_owner', '@@owner',
    'https://t.me/anna_owner', 't.me/anna_owner', 'tg://resolve?domain=anna_owner',
    'javascript:alert(1)', 'anna_owner/other', 'anna_owner?start=test', 'anna_owner#other',
    'anna_owner@evil.test', 'anna owner', 'анна_владелец', 'anna\nowner', '<b>owner</b>',
    'anna%2fowner', 'anna_owner\\other', 'anna_owner\u200b'
  ]) {
    expect(telegramUsernameUrl(value), String(value)).toBeNull()
  }
})
