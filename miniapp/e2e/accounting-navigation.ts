import type { Locator } from '@playwright/test'

// Older dates are disclosures; payment tests open the date before acting on its exact sale.
export async function revealAccountingSale(row: Locator) {
  const group = row.locator('xpath=ancestor::details[contains(@class,"accounting-day-group")]')
  if (await group.count() && await group.getAttribute('open') === null) await group.locator('summary').click()
  return row
}
export async function openAccountingSale(row: Locator) { await (await revealAccountingSale(row)).click() }
