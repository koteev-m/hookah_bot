import { expect, test } from '@playwright/test'

import bookingLabelFixture from '../../backend/app/src/test/resources/booking-display-label-cases.json' with { type: 'json' }
import { bookingDisplayLabel } from '../src/shared/ui/bookingLabel'

type BookingLabelCase = (typeof bookingLabelFixture.cases)[number]

function labelSource(labelCase: BookingLabelCase) {
  return {
    bookingId: labelCase.bookingId,
    displayNumber: labelCase.displayNumber,
    displayLabel: labelCase.displayLabel,
    scheduledAt: labelCase.scheduledAt,
    scheduledAtDisplay: labelCase.scheduledAtDisplay,
    legacyLabel: labelCase.legacyLabel
  }
}

test('shared booking label fixture stays aligned with the production TypeScript helper', () => {
  for (const labelCase of bookingLabelFixture.cases) {
    expect(bookingDisplayLabel(labelSource(labelCase)), labelCase.id).toBe(labelCase.expectedLabel)
  }
})

test('authoritative booking labels do not change with the browser timezone', async ({ browser }) => {
  const expectedLabels = bookingLabelFixture.cases.map((labelCase) => labelCase.expectedLabel)

  for (const timezoneId of bookingLabelFixture.browserTimezones) {
    const context = await browser.newContext({ timezoneId })
    const page = await context.newPage()
    try {
      await page.goto('./')
      const actualLabels = await page.evaluate(
        async ({ cases, moduleUrl }) => {
          const labelModule = await import(moduleUrl)
          return cases.map((labelCase) =>
            labelModule.bookingDisplayLabel({
              bookingId: labelCase.bookingId,
              displayNumber: labelCase.displayNumber,
              displayLabel: labelCase.displayLabel,
              scheduledAt: labelCase.scheduledAt,
              scheduledAtDisplay: labelCase.scheduledAtDisplay,
              legacyLabel: labelCase.legacyLabel
            })
          )
        },
        {
          cases: bookingLabelFixture.cases,
          moduleUrl: '/miniapp/src/shared/ui/bookingLabel.ts'
        }
      )
      expect(actualLabels, timezoneId).toEqual(expectedLabels)
    } finally {
      await context.close()
    }
  }
})
