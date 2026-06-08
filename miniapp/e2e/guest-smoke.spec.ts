import { expect, type Page, test } from '@playwright/test'

const sessionExpiresAt = Math.floor(Date.now() / 1000) + 3600
const tableToken = 'TABLE-SMOKE-1'
const mockInitData = 'query_id=e2e-smoke&user=%7B%22id%22%3A123456789%7D&hash=test'
const otherMockInitData = 'query_id=e2e-smoke-other&user=%7B%22id%22%3A987654321%7D&hash=test'
const transparentPng = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/luzRygAAAABJRU5ErkJggg==',
  'base64'
)

type RestoreContext = {
  tableToken: string
  tabId: number
  venueId: number
  venueName: string
  tableId: number
  tableSessionId: number
  tableSessionStatus: string
  tableSessionActive: boolean
  tableNumber: string
  venueStatus: string
  subscriptionStatus: string
  available: boolean
  unavailableReason: string | null
}

type TestTelegramWindow = Window & {
  Telegram?: {
    WebApp?: {
      initData?: string
      initDataUnsafe?: {
        user?: {
          id?: number
        }
      }
      ready?: () => void
      expand?: () => void
    }
  }
}

function jsonResponse(data: unknown) {
  return {
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify(data)
  }
}

function buildRestoreContext(overrides: Partial<RestoreContext> = {}): RestoreContext {
  return {
    tableToken,
    tabId: 88,
    venueId: 1,
    venueName: 'Микс',
    tableId: 7,
    tableSessionId: 77,
    tableSessionStatus: 'ACTIVE',
    tableSessionActive: true,
    tableNumber: '4',
    venueStatus: 'PUBLISHED',
    subscriptionStatus: 'ACTIVE',
    available: true,
    unavailableReason: null,
    ...overrides
  }
}

async function installTelegramWebApp(page: Page, userId: number) {
  await page.addInitScript(
    ({ initData, telegramUserId }) => {
      const telegramWindow = window as TestTelegramWindow
      const storedUserId = Number(window.localStorage.getItem('__e2e_telegram_user_id'))
      const nextUserId = Number.isFinite(storedUserId) && storedUserId > 0 ? storedUserId : telegramUserId
      const nextInitData = window.localStorage.getItem('__e2e_telegram_init_data') || initData
      telegramWindow.Telegram = {
        WebApp: {
          initData: nextInitData,
          initDataUnsafe: { user: { id: nextUserId } },
          ready: () => undefined,
          expand: () => undefined
        }
      }
    },
    { initData: mockInitData, telegramUserId: userId }
  )
}

async function mockGuestApi(page: Page, options: { restoreContext?: RestoreContext | null } = {}) {
  let structuredMenuCalls = 0
  let restoreContext = options.restoreContext ?? null

  await page.route('**/api/auth/telegram', async (route) => {
    await route.fulfill(jsonResponse({ token: 'e2e-session-token', expiresAtEpochSeconds: sessionExpiresAt }))
  })

  await page.route('**/api/guest/catalog', async (route) => {
    await route.fulfill(
      jsonResponse({
        venues: [
          {
            id: 1,
            name: 'Микс',
            city: 'Москва',
            address: 'Пилотная, 1',
            cardDescription: 'Тестовая карточка'
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/venue/1', async (route) => {
    await route.fulfill(
      jsonResponse({
        venue: {
          id: 1,
          name: 'Микс',
          city: 'Москва',
          address: 'Пилотная, 1',
          guestContact: '+7 000 000-00-00',
          cardDescription: 'Текстовая информация о заведении',
          status: 'PUBLISHED'
        }
      })
    )
  })

  await page.route('**/api/guest/venue/1/info-sections', async (route) => {
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        sections: [
          {
            id: 10,
            type: 'menu',
            title: 'Меню',
            displayTitle: '📖 Фото-меню',
            text: 'Ознакомительное меню в карточке',
            mediaCount: 1,
            media: [
              {
                id: 100,
                mediaType: 'image',
                sortOrder: 0,
                url: '/api/guest/venue/1/info-sections/10/media/100'
              }
            ]
          }
        ]
      })
    )
  })

  await page.route('**/api/guest/venue/1/info-sections/10/media/100', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'image/png',
      body: transparentPng
    })
  })

  await page.route('**/api/guest/table/resolve?**', async (route) => {
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        venueName: 'Микс',
        tableId: 7,
        tableSessionId: 77,
        tableSessionStatus: 'ACTIVE',
        tableSessionActive: true,
        tableNumber: '4',
        venueStatus: 'PUBLISHED',
        subscriptionStatus: 'ACTIVE',
        available: true,
        unavailableReason: null
      })
    )
  })

  await page.route('**/api/guest/table/restore', async (route) => {
    await route.fulfill(jsonResponse({ context: restoreContext }))
  })

  await page.route('**/api/guest/venue/1/menu', async (route) => {
    structuredMenuCalls += 1
    await route.fulfill(
      jsonResponse({
        venueId: 1,
        categories: [
          {
            id: 20,
            name: 'Кальянное меню',
            items: [
              {
                id: 200,
                name: 'Double Apple',
                priceMinor: 150000,
                currency: 'RUB',
                isAvailable: true
              }
            ]
          }
        ]
      })
    )
  })

  return {
    getStructuredMenuCalls: () => structuredMenuCalls,
    setRestoreContext: (context: RestoreContext | null) => {
      restoreContext = context
    }
  }
}

test('pre-QR guest card shows info/photo menu and hides structured order menu', async ({ page }) => {
  const api = await mockGuestApi(page)

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByRole('heading', { name: 'Hookah Mini App' })).toBeVisible()
  await page.getByRole('button', { name: 'Открыть карточку' }).click()

  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'ℹ️ Информация' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '📖 Фото-меню' })).toBeVisible()
  await expect(page.getByText('Заказное меню и корзина доступны после сканирования QR-кода на столе.')).toBeVisible()
  await expect(page.getByAltText('📖 Фото-меню 1')).toBeVisible()
  await expect(page.getByText('Кальянное меню')).toHaveCount(0)
  expect(api.getStructuredMenuCalls()).toBe(0)
})

test('table context opens category-first order menu and cart action', async ({ page }) => {
  await mockGuestApi(page)

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: 'Меню', exact: true }).click()

  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toHaveCount(0)
  await page.getByRole('button', { name: /Кальянное меню/ }).click()

  await expect(page.getByRole('heading', { name: 'Кальянное меню' })).toBeVisible()
  await expect(page.getByText('Double Apple')).toBeVisible()
  await page.getByRole('button', { name: 'Добавить' }).click()

  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()
})

test('startup without URL table token restores active table context', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Микс' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Выберите раздел меню' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина' })).toBeVisible()
})

test('explicit QR table token wins over restore context', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  await mockGuestApi(page, {
    restoreContext: buildRestoreContext({
      tableToken: 'RESTORE-OLD',
      tableSessionId: 901,
      tabId: 902,
      tableNumber: '99'
    })
  })

  await page.goto(`?mode=guest&screen=menu&table_token=${tableToken}#tgWebAppData=${encodeURIComponent(mockInitData)}`)

  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await expect(page.getByText('столом №99')).toHaveCount(0)
})

test('account switch does not reuse previous user cart or table restore state', async ({ page }) => {
  await installTelegramWebApp(page, 123456789)
  const api = await mockGuestApi(page, { restoreContext: buildRestoreContext() })

  await page.goto(`?mode=guest#tgWebAppData=${encodeURIComponent(mockInitData)}`)
  await expect(page.getByText('Вы за столом №4 · Микс')).toBeVisible()
  await page.getByRole('button', { name: /Кальянное меню/ }).click()
  await page.getByRole('button', { name: 'Добавить' }).click()
  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toBeVisible()

  api.setRestoreContext(null)
  await page.evaluate((initData) => {
    window.localStorage.setItem('__e2e_telegram_user_id', '987654321')
    window.localStorage.setItem('__e2e_telegram_init_data', initData)
  }, otherMockInitData)
  await page.goto(`?mode=guest&smokeUser=other#tgWebAppData=${encodeURIComponent(otherMockInitData)}`)

  await expect(page.getByText('Чтобы заказать к столику или вызвать персонал, отсканируйте QR-код на столе.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Корзина (1)' })).toHaveCount(0)
})
