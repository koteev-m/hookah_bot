export type LocalCountryOption = {
  code: string
  name: string
  search: string[]
}

export type LocalCityOption = {
  countryCode: string
  name: string
  region?: string
  search: string[]
}

/*
 * Provider-free location seed data.
 *
 * Country codes are ISO 3166-1 alpha-2 factual identifiers; Russian display
 * names and search aliases are project-authored labels. City names are a small
 * curated application seed for convenience, not a proprietary geodata dump and
 * not a verification source. Missing settlements remain manually enterable.
 */
export const LOCAL_COUNTRIES: LocalCountryOption[] = [
  { code: 'RU', name: 'Россия', search: ['россия', 'russia', 'ru', 'рф'] },
  { code: 'KZ', name: 'Казахстан', search: ['казахстан', 'kazakhstan', 'kz'] },
  { code: 'BY', name: 'Беларусь', search: ['беларусь', 'belarus', 'by'] },
  { code: 'AM', name: 'Армения', search: ['армения', 'armenia', 'am'] },
  { code: 'GE', name: 'Грузия', search: ['грузия', 'georgia', 'ge'] },
  { code: 'UZ', name: 'Узбекистан', search: ['узбекистан', 'uzbekistan', 'uz'] },
  { code: 'AE', name: 'ОАЭ', search: ['оаэ', 'эмираты', 'uae', 'united arab emirates', 'ae'] },
  { code: 'TR', name: 'Турция', search: ['турция', 'turkey', 'türkiye', 'tr'] }
]

export const LOCAL_CITIES: LocalCityOption[] = [
  { countryCode: 'RU', name: 'Москва', search: ['москва', 'moscow'] },
  { countryCode: 'RU', name: 'Санкт-Петербург', search: ['санкт-петербург', 'петербург', 'спб', 'saint petersburg', 'spb'] },
  { countryCode: 'RU', name: 'Казань', search: ['казань', 'kazan'] },
  { countryCode: 'RU', name: 'Екатеринбург', search: ['екатеринбург', 'ekaterinburg', 'yekaterinburg'] },
  { countryCode: 'RU', name: 'Новосибирск', search: ['новосибирск', 'novosibirsk'] },
  { countryCode: 'RU', name: 'Нижний Новгород', search: ['нижний новгород', 'nizhny novgorod'] },
  { countryCode: 'RU', name: 'Краснодар', search: ['краснодар', 'krasnodar'] },
  { countryCode: 'RU', name: 'Ростов-на-Дону', search: ['ростов-на-дону', 'ростов', 'rostov-on-don'] },
  { countryCode: 'RU', name: 'Сочи', search: ['сочи', 'sochi'] },
  { countryCode: 'KZ', name: 'Алматы', search: ['алматы', 'almaty'] },
  { countryCode: 'KZ', name: 'Астана', search: ['астана', 'astana'] },
  { countryCode: 'BY', name: 'Минск', search: ['минск', 'minsk'] },
  { countryCode: 'AM', name: 'Ереван', search: ['ереван', 'yerevan'] },
  { countryCode: 'GE', name: 'Тбилиси', search: ['тбилиси', 'tbilisi'] },
  { countryCode: 'UZ', name: 'Ташкент', search: ['ташкент', 'tashkent'] },
  { countryCode: 'AE', name: 'Дубай', search: ['дубай', 'dubai'] },
  { countryCode: 'TR', name: 'Стамбул', search: ['стамбул', 'istanbul'] }
]

export function countryByCode(code: string | null | undefined): LocalCountryOption | null {
  const normalized = code?.trim().toUpperCase()
  if (!normalized) return null
  return LOCAL_COUNTRIES.find((country) => country.code === normalized) ?? null
}

export function countryName(code: string | null | undefined): string {
  return countryByCode(code)?.name ?? code?.trim().toUpperCase() ?? ''
}

export function filterCountries(query: string): LocalCountryOption[] {
  const normalized = query.trim().toLowerCase()
  if (normalized.length < 2) return []
  return LOCAL_COUNTRIES.filter((country) => {
    return country.name.toLowerCase().includes(normalized) || country.search.some((value) => value.includes(normalized))
  }).slice(0, 7)
}

export function filterCities(countryCode: string | null | undefined, query: string): LocalCityOption[] {
  const normalizedCountry = countryCode?.trim().toUpperCase()
  const normalizedQuery = query.trim().toLowerCase()
  if (!normalizedCountry || normalizedQuery.length < 2) return []
  return LOCAL_CITIES.filter((city) => {
    return (
      city.countryCode === normalizedCountry &&
      (city.name.toLowerCase().includes(normalizedQuery) ||
        city.search.some((value) => value.includes(normalizedQuery)))
    )
  }).slice(0, 7)
}
