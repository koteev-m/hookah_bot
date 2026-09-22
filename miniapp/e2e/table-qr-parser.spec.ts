import { expect, test } from '@playwright/test'
import fixture from './fixtures/table-qr.json' with { type: 'json' }
import { extractTableTokenFromScannedText, tableTokenQueryParamKeys } from '../src/shared/validation/tableToken'

const context = { miniAppUrl: 'https://miniapp.example/miniapp/', botUsername: fixture.botUsername }

test('table QR parser preserves generated bot, Mini App and raw token formats with one URL decode', () => {
  const accepted = [
    fixture.qrText,
    fixture.tableToken,
    `  ${fixture.tableToken}  `,
    `https://t.me/${fixture.botUsername}?startapp=${fixture.tableToken}`,
    `https://t.me/${fixture.botUsername}?start=%68tqr01_synthetic_table_token`,
    ...tableTokenQueryParamKeys.map((key) => `${context.miniAppUrl}?${key}=${fixture.tableToken}#/venue/1`)
  ]
  for (const value of accepted) {
    expect(extractTableTokenFromScannedText(value, context), value).toBe(fixture.tableToken)
  }
  expect(extractTableTokenFromScannedText(fixture.qrText, { ...context, botUsername: null })).toBe(fixture.tableToken)
})

test('table QR parser rejects empty, foreign, conflicting, malformed and double-encoded payloads', () => {
  const rejected = [
    '', ' ', 'токен', 'has space', 'x'.repeat(129), 'https://',
    'javascript:alert(1)', `tg://resolve?domain=${fixture.botUsername}&start=${fixture.tableToken}`,
    `https://foreign.example/?table_token=${fixture.tableToken}`,
    `https://t.me/foreign_bot?start=${fixture.tableToken}`,
    `http://t.me/${fixture.botUsername}?start=${fixture.tableToken}`,
    `https://t.me.evil.example/${fixture.botUsername}?start=${fixture.tableToken}`,
    `https://user@t.me/${fixture.botUsername}?start=${fixture.tableToken}`,
    `https://t.me:444/${fixture.botUsername}?start=${fixture.tableToken}`,
    `https://t.me/${fixture.botUsername}/other?start=${fixture.tableToken}`,
    `https://t.me/${fixture.botUsername}?start=${fixture.tableToken}#other`,
    `https://t.me/${fixture.botUsername}?start=`,
    `${fixture.qrText}&start=other`, `${fixture.qrText}&start=${fixture.tableToken}`,
    `${fixture.qrText}&startapp=other`, `${fixture.qrText}&table_token=other`,
    `https://t.me/${fixture.botUsername}?start=%2568tqr01_synthetic_table_token`,
    `https://t.me/${fixture.botUsername}?start=%ZZ`,
    `${context.miniAppUrl}?tableToken=${fixture.tableToken}&table_token=other`,
    `${context.miniAppUrl}?table_token=${fixture.tableToken}&start=other`,
    `${context.miniAppUrl}other?table_token=${fixture.tableToken}`
  ]
  for (const value of rejected) {
    expect(extractTableTokenFromScannedText(value, context), value).toBeNull()
  }
})
