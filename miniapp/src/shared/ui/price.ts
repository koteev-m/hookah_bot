export function formatPrice(priceMinor: number, currency: string): string {
  const value = Number.isFinite(priceMinor) ? priceMinor / 100 : 0
  try {
    return new Intl.NumberFormat('ru-RU', {
      style: 'currency',
      currency
    }).format(value)
  } catch {
    return `${value.toFixed(2)} ${currency}`
  }
}
