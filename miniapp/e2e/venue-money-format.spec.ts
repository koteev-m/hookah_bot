import { expect, test } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { exactMoney } from '../src/shared/ui/venueStatsFormat'
import iso4217 from '../src/shared/ui/iso4217MinorUnits.json' with { type: 'json' }

const numeric = (value: string) => value.replace(/[^\d,−-]/g, '').replace('−', '-')
const cases = [
  ['IQD', 3, '12,345'], ['MGA', 2, '123,45'], ['RUB', 2, '123,45'],
  ['EUR', 2, '123,45'], ['USD', 2, '123,45'], ['JPY', 0, '12345'],
  ['KRW', 0, '12345'], ['KWD', 3, '12,345'], ['BHD', 3, '12,345'], ['CLF', 4, '1,2345']
] as const

for (const [currency, , value] of cases) test(`money ISO ${currency} preserves the numeric value of 12345 minor units`, () => {
  expect(numeric(exactMoney('12345', currency))).toBe(value)
})

test('money ISO zero one negative and Long boundaries remain exact for every supported exponent', () => {
  for (const [currency, digits] of cases) {
    const expected = (minor: string) => {
      const abs = minor.replace('-', '').padStart(digits + 1, '0')
      return (minor.startsWith('-') ? '-' : '') + (digits ? `${abs.slice(0, -digits)},${abs.slice(-digits)}` : abs)
    }
    for (const minor of ['0', '1', '-1', '900719925474099312', '9223372036854775807', '-9223372036854775808']) {
      expect(numeric(exactMoney(minor, currency)), `${currency} ${minor}`).toBe(expected(minor))
    }
    expect(numeric(exactMoney((10n ** BigInt(digits)).toString(), currency))).toBe(digits ? `1,${'0'.repeat(digits)}` : '1')
  }
})

test('money ISO unknown invalid and nonapplicable currency or amount never guesses a scale', () => {
  for (const currency of ['ZZZ', 'BTC', 'XXX', 'XTS', 'XAU', 'XAG', 'usd', ' USD ', '', 'toString']) {
    expect(exactMoney('12345', currency), currency).toBe('Недостаточно данных')
  }
  for (const value of ['NaN', 'Infinity', '12.345', '1e3', '', ' 1', '+1']) {
    expect(exactMoney(value, 'RUB'), value).toBe('Недостаточно данных')
  }
})

for (const locale of ['ru-RU', 'en-US', 'de-DE']) test(`money ISO scale ignores Intl defaults and resolved options in ${locale}`, () => {
  const Original = Intl.NumberFormat
  try {
    for (const misleadingDigits of [0, 2, 4]) {
      Intl.NumberFormat = function (_locale: Intl.LocalesArgument, options: Intl.NumberFormatOptions) {
        const formatter = new Original(locale, { minimumFractionDigits: misleadingDigits, maximumFractionDigits: misleadingDigits, ...options })
        const resolved = formatter.resolvedOptions.bind(formatter)
        formatter.resolvedOptions = () => ({ ...resolved(), maximumFractionDigits: misleadingDigits })
        return formatter
      } as typeof Intl.NumberFormat
      const decimal = new Original(locale).formatToParts(1.5).find(part => part.type === 'decimal')!.value
      const group = new Original(locale).formatToParts(10000).find(part => part.type === 'group')!.value
      for (const [currency, , expected] of cases) {
        const actual = exactMoney('12345', currency).split(group).join('').replace(decimal, ',')
        expect(numeric(actual), `${currency}, misleading default ${misleadingDigits}`).toBe(expected)
      }
    }
  } finally { Intl.NumberFormat = Original }
})

test('money ISO checked-in exponents exactly match the pinned SIX source including NA entries', () => {
  const xml = readFileSync(new URL('./fixtures/iso4217-list-one.xml', import.meta.url), 'utf8')
  expect(createHash('sha256').update(xml).digest('hex')).toBe(iso4217.lfSnapshotSha256)
  expect(xml).toContain(`Pblshd="${iso4217.published}"`)
  const source: Record<string, number | null> = {}
  for (const [entry] of xml.matchAll(/<CcyNtry>[\s\S]*?<\/CcyNtry>/g)) {
    const code = entry.match(/<Ccy>([A-Z]{3})<\/Ccy>/)?.[1]
    if (!code) continue
    const unit = entry.match(/<CcyMnrUnts>(.*?)<\/CcyMnrUnts>/)![1]
    const digits = unit === 'N.A.' ? null : Number(unit)
    if (code in source) expect(digits).toBe(source[code])
    source[code] = digits
  }
  expect(source).toEqual(iso4217.minorUnits)
  expect(Object.keys(source)).toHaveLength(178)
})
