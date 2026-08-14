export function formatVenueCount(count: number) {
  const absolute = Math.abs(Math.trunc(count))
  const lastTwo = absolute % 100
  const last = absolute % 10
  const noun =
    lastTwo >= 11 && lastTwo <= 14
      ? 'заведений'
      : last === 1
        ? 'заведение'
        : last >= 2 && last <= 4
          ? 'заведения'
          : 'заведений'

  return `${count} ${noun}`
}
