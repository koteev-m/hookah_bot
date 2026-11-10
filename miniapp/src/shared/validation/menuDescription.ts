export function normalizeMenuDescription(value: string): string {
  return value.replace(/[\p{White_Space}\p{Cc}]/gu, ' ').replace(/^ +| +$/g, '').replace(/ +/g, ' ')
}

export function menuDescriptionLength(value: string): number {
  return Array.from(normalizeMenuDescription(value)).length
}
