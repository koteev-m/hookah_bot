type BookingLabelSource = {
  bookingId?: number | null
  displayNumber?: number | null
  displayLabel?: string | null
  scheduledAt?: string | null
  scheduledAtDisplay?: string | null
  legacyLabel?: string | null
}

function normalizedText(value?: string | null): string | null {
  const normalized = value?.trim()
  return normalized ? normalized : null
}

function normalizedPositiveInteger(value?: number | null): number | null {
  return value != null && Number.isSafeInteger(value) && value > 0 ? value : null
}

export function bookingDisplayLabel(source: BookingLabelSource): string {
  const authoritativeLabel = normalizedText(source.displayLabel)
  if (authoritativeLabel) return authoritativeLabel

  const displayNumber = normalizedPositiveInteger(source.displayNumber)
  const bookingId = normalizedPositiveInteger(source.bookingId)
  const scheduledAt = normalizedText(source.scheduledAtDisplay)
  const identity = displayNumber != null && scheduledAt != null
    ? `Бронь №${displayNumber}`
    : bookingId != null
      ? `Бронь #${bookingId}`
      : displayNumber != null
        ? `Бронь №${displayNumber}`
      : normalizedText(source.legacyLabel) ?? 'Бронь'
  return scheduledAt ? `${identity} · ${scheduledAt}` : identity
}
