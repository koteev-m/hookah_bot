import { expect, test, type Page } from '@playwright/test'
import wire from './fixtures/seating-http.json' with { type: 'json' }
import type { HallState, SeatingCommand } from '../src/shared/api/seating'

test.use({ viewport: { width: 390, height: 844 } })
const initial = () => structuredClone(wire.empty) as HallState
const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII=', 'base64')
async function mount(page: Page, mode = 'work', role = 'OWNER', venue = 1, navigate = true, user = 1001) {
  await page.route('**/miniapp/hall-test', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: '<html><head><meta charset="utf-8"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><div id="test-root"></div></body></html>' }))
  if (navigate) await page.goto('hall-test')
  await page.evaluate(async ({ mode, role, venue, user }) => {
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:unknown`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
    const themePath = '/miniapp/src/shared/ui/appTheme.ts'
    const { bindAppTheme } = await import(themePath)
    Reflect.get(window, 'themeDispose')?.()
    Reflect.set(window, 'themeDispose', bindAppTheme(document.documentElement))
    const path = '/miniapp/src/screens/venueHall.ts'
    const module = await import(path)
    Reflect.get(window, 'hallDispose')?.()
    Reflect.set(window, 'hallDispose', module.renderVenueHall({ root: document.querySelector('#test-root'), backendUrl: location.origin, venueId: venue, userId: user, mode,
      access: { venueId: venue, role, permissions: ['TABLE_VIEW', 'BOOKING_VIEW', 'BOOKING_MANUAL_CREATE'] } }))
  }, { mode, role, venue, user })
}
async function api(page: Page, state: HallState, mutate?: (cmd: SeatingCommand) => void) {
  const commands: SeatingCommand[] = []
  await page.route('**/api/venue/*/hall', r => r.fulfill({ json: state }))
  await page.route('**/api/venue/*/hall/commands', async r => { const cmd = r.request().postDataJSON(); commands.push(cmd); mutate?.(cmd); await r.fulfill({ json: { id: cmd.id ?? 10, revision: (cmd.revision ?? -1) + 1 } }) })
  await page.route('**/api/venue/*/hall/*/plans/*', r => r.fulfill({ contentType: 'image/png', body: image }))
  return commands
}

test('real wire defaults show unknown values and separate future reserve from occupied state', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  state.tables[0].capacity = null
  await api(page, state); await mount(page)
  await expect(page.getByText('Мест: неизвестно · Свободен сейчас')).toBeVisible()
  await expect(page.getByText('Создана сотрудником', { exact: false })).toBeVisible()
  await expect(page.getByText('+70000000000', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Добавить бронь', exact: true })).toBeVisible()
})

test('manual booking keeps input and uses one key after lost response and reload', async ({ page }) => {
  const state = initial(); await api(page, state)
  const sent: SeatingCommand[] = []
  await page.route('**/api/venue/1/hall/commands', async r => { sent.push(r.request().postDataJSON()); if (sent.length === 1) await r.abort('failed'); else await r.fulfill({ json: { id: 9, revision: 0 } }) })
  await mount(page); await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click()
  await page.getByLabel('Имя или обозначение гостя').fill('Телефонный гость')
  await page.getByLabel('Телефон (необязательно)', { exact: true }).fill('+70000000000')
  await page.getByLabel('Начало (время заведения)', { exact: true }).fill('2030-01-07T20:00')
  await page.getByLabel('Стол для гостей').selectOption('2')
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click()
  await expect(page.getByRole('button', { name: 'Проверить результат повтором' })).toBeVisible()
  await mount(page, 'work')
  await page.getByRole('button', { name: 'Проверить результат повтором' }).click()
  await expect.poll(() => sent.length).toBe(2)
  expect(sent[1]).toEqual(sent[0])
  expect(sent[0]).toMatchObject({ action: 'MANUAL_BOOKING', tableId: 2, name: 'Телефонный гость', phone: '+70000000000', localStart: '2030-01-07T20:00' })
})

test('table editor restores draft after reload and saves geometry without changing identity', async ({ page }) => {
  const state = initial(); state.halls = [{ id: 1, name: 'Главный', active: true, revision: 0, planId: 'synthetic', planWidth: 800, planHeight: 400 }]
  const commands = await api(page, state, cmd => { if (cmd.action === 'TABLE') { const t = state.tables.find(t => t.id === cmd.id)!; t.layout = cmd.layout!; t.hallId = cmd.hallId!; t.capacity = cmd.capacity!; t.revision++ } })
  await mount(page, 'settings'); await page.getByRole('button', { name: 'Редактировать стол' }).first().click()
  await page.getByLabel('Зал', { exact: true }).selectOption('1')
  await page.getByLabel('Форма', { exact: true }).selectOption('ELLIPSE')
  await page.getByLabel('Положение X (0–1)').fill('0.3')
  await page.getByLabel('Положение Y (0–1)').fill('0.4')
  await page.getByLabel('Поворот, градусы').fill('45')
  await mount(page, 'settings')
  await expect(page.getByLabel('Поворот, градусы')).toHaveValue('45')
  await page.getByRole('button', { name: 'Сохранить стол' }).click()
  await expect.poll(() => commands.length).toBe(1)
  expect(commands[0]).toMatchObject({ action: 'TABLE', id: 1, hallId: 1, layout: { x: .3, y: .4, rotation: 45, shape: 'ELLIPSE' } })
  await page.getByRole('button', { name: 'Схема', exact: true }).click()
  const marker = page.locator('.hall-marker').first()
  expect(await marker.evaluate(e => (e as HTMLElement).style.left)).toBe('30%')
  await page.getByLabel('Масштаб схемы').fill('2')
  await page.getByRole('button', { name: 'Вписать схему' }).click()
  expect(commands).toHaveLength(1)
  await expect(marker).toBeVisible()
})

test('rule guidance false zero and staff boundary', async ({ page }) => {
  const state = initial(); const commands = await api(page, state)
  await mount(page, 'rules')
  await expect(page.getByText('Стол не освобождается автоматически по таймеру', { exact: false })).toBeVisible()
  await page.getByLabel('Использовать схему зала', { exact: true }).check()
  await page.getByLabel('Ограничивать длительность визита', { exact: true }).uncheck()
  await page.getByLabel('Перерыв между посадками, минут').fill('0')
  await page.getByRole('button', { name: 'Сохранить правила' }).click()
  await expect.poll(() => commands.length).toBe(1)
  expect(commands[0].rules).toMatchObject({ usePlan: true, limitDuration: false, bufferMinutes: 0 })
  await mount(page, 'rules', 'STAFF')
  await expect(page.getByRole('button', { name: 'Сохранить правила' })).toHaveCount(0)
})

test('upload previews before explicit confirmation and preserves old plan on upload failure', async ({ page }) => {
  const state = initial(); state.halls = [{ id: 1, name: 'Главный', active: true, revision: 3, planId: 'old', planWidth: 600, planHeight: 300 }]
  const commands = await api(page, state)
  let attempts = 0
  await page.route('**/api/venue/1/hall/1/plans', async r => { attempts++; if (attempts === 1) await r.fulfill({ status: 503, json: { error: { message: 'Хранилище недоступно' } } }); else await r.fulfill({ json: { id: 'new', width: 600, height: 300 } }) })
  await mount(page, 'settings'); await page.getByRole('button', { name: 'Загрузить или заменить план' }).click()
  await page.getByLabel('Изображение плана').setInputFiles({ name: 'plan.png', mimeType: 'image/png', buffer: image })
  await expect(page.getByAltText('Предпросмотр новой схемы')).toBeVisible()
  await page.getByRole('button', { name: 'Загрузить выбранный план' }).click()
  await expect(page.getByText('Хранилище недоступно')).toBeVisible(); expect(commands).toHaveLength(0)
  await page.getByRole('button', { name: 'Загрузить выбранный план' }).click()
  await expect(page.getByRole('button', { name: 'Подтвердить новую схему и разместить столы заново' })).toBeVisible(); expect(commands).toHaveLength(0)
  await page.getByRole('button', { name: 'Подтвердить новую схему и разместить столы заново' }).click()
  await expect.poll(() => commands.length).toBe(1); expect(commands[0]).toMatchObject({ action: 'PLAN', revision: 3, planId: 'new', confirmPlanReplacement: true })
})

for (const theme of ['light', 'dark'] as const) test(`mobile ${theme} table operation conflict retains agreed end and release guidance`, async ({ page }) => {
  const state = initial(); const table = state.tables[0]; table.state = 'OCCUPIED'; table.seating = { id: 10, tableId: 1, visitId: 12, tableSessionId: 13, bookingId: null, partySize: 2, startedAt: '2030-01-07T15:00:00Z', agreedEndAt: '2030-01-07T17:00:00Z', revision: 0 }
  await api(page, state); await page.route('**/api/venue/1/hall/commands', r => r.fulfill({ status: 409, json: { error: { code: 'SEATING_CONFLICT', message: 'Стол зарезервирован. Выберите другой стол.' } } }))
  await page.emulateMedia({ colorScheme: theme }); await mount(page, 'work', 'STAFF')
  await expect(page.locator('html')).toHaveAttribute('data-color-scheme', theme)
  await page.getByRole('button', { name: 'Действия со столом' }).first().click()
  await page.getByLabel('Новое окончание (время заведения)').fill('2030-01-07T20:30')
  await page.getByRole('button', { name: 'Продлить посадку' }).click()
  await expect(page.getByText('Стол зарезервирован. Выберите другой стол.')).toBeVisible()
  await expect(page.getByLabel('Новое окончание (время заведения)')).toHaveValue('2030-01-07T20:30')
  await expect(page.getByText('Освобождение не является оплатой.', { exact: false })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})

test('revoked access clears persisted drafts and ignores a disposed venue response', async ({ page }) => {
  const state = initial(); await api(page, state); await mount(page)
  await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click(); await page.getByLabel('Имя или обозначение гостя').fill('Private draft')
  await page.route('**/api/venue/1/hall', r => r.fulfill({ status: 403, json: { error: { message: 'Доступ отозван' } } }))
  await page.getByRole('button', { name: 'Обновить доступность' }).click(); await expect(page.getByText('Доступ отозван')).toBeVisible()
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveCount(0)
  expect(await page.evaluate(() => sessionStorage.getItem('hookah:hall:1001:1:work'))).toBeNull()
  await mount(page, 'work', 'OWNER', 2); await expect(page.getByText('Private draft')).toHaveCount(0)
})

async function mountGuestChoice(page: Page, navigate = true) {
  await page.route('**/miniapp/guest-hall-test', r => r.fulfill({ contentType: 'text/html; charset=utf-8', body: '<html><head><meta charset="utf-8"><link rel="stylesheet" href="/miniapp/src/style.css"><link rel="stylesheet" href="/miniapp/src/theme.css"></head><body><section id="form"><label>Дата<input id="date" type="date" value="2030-01-07"></label><label>Время<input id="time" type="time" value="20:00"></label><label>Гостей<input id="party" type="number" value="2"></label><label>Комментарий<textarea id="comment"></textarea></label><p id="status"></p></section><button id="submit">Отправить заявку</button></body></html>' }))
  if (navigate) await page.goto('guest-hall-test')
  await page.evaluate(async () => {
    Reflect.set(window, 'Telegram', { WebApp: { initData: 'synthetic', initDataUnsafe: { user: { id: 1003 } } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:1003`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
    const themePath = '/miniapp/src/shared/ui/appTheme.ts'
    const { bindAppTheme } = await import(themePath)
    bindAppTheme(document.documentElement)
    const path = '/miniapp/src/shared/ui/guestTableChoice.ts'
    const { guestTableChoice } = await import(path)
    const ui = guestTableChoice(location.origin, 1, { formCard: document.querySelector('#form'), dateInput: document.querySelector('#date'), timeInput: document.querySelector('#time'), partySizeInput: document.querySelector('#party'), commentInput: document.querySelector('#comment'), status: document.querySelector('#status') })
    Reflect.set(window, 'guestChoiceDispose', ui.dispose)
    document.querySelector('#submit')!.addEventListener('click', () => { void ui.submit() })
  })
}

const guestAvailability = () => ({ venueId: 1, timezone: 'Europe/Moscow', enabled: true, scheduledAt: '2030-01-07T17:00:00Z', agreedEndAt: '2030-01-07T19:00:00Z', reason: null, halls: [], tables: initial().tables.map(t => ({ ...t, state: t.id === 2 ? 'UNAVAILABLE' : 'AVAILABLE', reason: t.id === 2 ? 'Стол зарезервирован.' : null })) })

test('guest selection does not reserve and conflict preserves inputs and permits another table', async ({ page }) => {
  await page.route('**/api/guest/venues/1/table-availability?*', r => r.fulfill({ json: guestAvailability() }))
  const sent: SeatingCommand[] = []
  await page.route('**/api/guest/venues/1/table-bookings', async r => { sent.push(r.request().postDataJSON()); if (sent.length === 1) await r.fulfill({ status: 409, json: { error: { code: 'SEATING_CONFLICT', message: 'Этот стол уже занят. Выберите другой.' } } }); else await r.fulfill({ json: { id: 20, revision: 0 } }) })
  await mountGuestChoice(page)
  await expect(page.getByText('Визит до', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: /Стол №3/ })).toBeDisabled()
  await page.getByLabel('Комментарий', { exact: true }).fill('Синтетический комментарий')
  await page.getByRole('button', { name: /Стол №1,/ }).click(); expect(sent).toHaveLength(0)
  await page.getByLabel('Условия времени и вместимости понятны').check()
  await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect(page.getByText('Этот стол уже занят. Выберите другой.')).toBeVisible()
  await expect(page.getByLabel('Комментарий', { exact: true })).toHaveValue('Синтетический комментарий')
  await page.getByRole('button', { name: /Стол №5,/ }).click()
  await page.getByLabel('Условия времени и вместимости понятны').check()
  await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect(page.getByText('Заявка на выбранный стол отправлена.', { exact: false })).toBeVisible()
  expect(sent[0].tableId).toBe(1); expect(sent[1].tableId).toBe(3)
})

test('unknown guest submit locks inputs and repeats original operation after reload even when feature was disabled', async ({ page }) => {
  let enabled = true
  await page.route('**/api/guest/venues/1/table-availability?*', r => r.fulfill({ json: { ...guestAvailability(), enabled } }))
  const sent: SeatingCommand[] = []
  await page.route('**/api/guest/venues/1/table-bookings', async r => { sent.push(r.request().postDataJSON()); if (sent.length === 1) await r.abort('failed'); else await r.fulfill({ json: { id: 20, revision: 0 } }) })
  await mountGuestChoice(page); await page.getByRole('button', { name: /Стол №1,/ }).click()
  await page.getByLabel('Условия времени и вместимости понятны').check(); await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect.poll(() => sent.length).toBe(1); await expect(page.getByLabel('Дата', { exact: true })).toBeDisabled()
  enabled = false
  await mountGuestChoice(page)
  await expect(page.getByLabel('Дата', { exact: true })).toBeDisabled()
  await page.getByRole('button', { name: 'Отправить заявку' }).click()
  await expect.poll(() => sent.length).toBe(2); expect(sent[1]).toEqual(sent[0])
})

test('late response from disposed venue cannot restore another venues content', async ({ page }) => {
  const state = initial(); await api(page, state); await mount(page)
  let release!: () => void
  let entered!: () => void
  const enteredPromise = new Promise<void>(resolve => { entered = resolve })
  const gate = new Promise<void>(resolve => { release = resolve })
  await page.route('**/api/venue/1/hall', async r => { entered(); await gate; await r.fulfill({ json: { ...state, tables: [{ ...state.tables[0], number: 999 }] } }) })
  await page.getByRole('button', { name: 'Обновить доступность' }).click(); await enteredPromise
  await page.route('**/api/venue/2/hall', r => r.fulfill({ json: { ...state, venueId: 2, tables: [{ ...state.tables[0], number: 42 }] } }))
  await mount(page, 'work', 'OWNER', 2, false)
  await expect(page.getByRole('heading', { name: /Стол №42/ })).toBeVisible()
  release()
  await expect(page.getByRole('heading', { name: /Стол №999/ })).toHaveCount(0)
})

test('dragged marker uses actual image coordinates at mobile zoom and survives explicit save', async ({ page }, testInfo) => {
  const state = initial(); state.halls = [{ id: 1, name: 'Длинный зал', active: true, revision: 0, planId: 'synthetic', planWidth: 800, planHeight: 400 }]
  Object.assign(state.tables[0], { hallId: 1, layout: { x: .25, y: .5, width: .12, height: .2, rotation: 0, shape: 'RECTANGLE' } })
  const commands = await api(page, state); await mount(page, 'settings')
  await page.getByRole('button', { name: 'Схема', exact: true }).click()
  const canvas = page.locator('.hall-plan-canvas'); const marker = page.locator('.hall-marker').first()
  const rect = (await canvas.boundingBox())!; const hit = (await marker.boundingBox())!
  expect(hit.width / rect.width).toBeCloseTo(.12, 2)
  expect(hit.height / rect.height).toBeCloseTo(.2, 2)
  const touch = await marker.evaluate(e => ({ width: parseFloat(getComputedStyle(e, '::before').width), height: parseFloat(getComputedStyle(e, '::before').height) }))
  expect(touch.width).toBeGreaterThanOrEqual(44); expect(touch.height).toBeGreaterThanOrEqual(44)
  await page.mouse.move(hit.x + hit.width / 2, hit.y + hit.height / 2); await page.mouse.down()
  await page.mouse.move(rect.x + rect.width * .65, rect.y + rect.height * .6, { steps: 8 }); await page.mouse.up()
  await expect.poll(async () => Number(await page.getByLabel('Положение X (0–1)').inputValue())).toBeCloseTo(.65, 1)
  await page.getByRole('button', { name: 'Сохранить стол' }).click(); await expect.poll(() => commands.length).toBe(1)
  expect(commands[0].layout!.x).toBeCloseTo(.65, 1); expect(commands[0].layout!.y).toBeCloseTo(.6, 1)
  await page.screenshot({ path: testInfo.outputPath('mobile-hall-editor.png'), fullPage: true })
})


test('walk in draft stays separate from booking with the same numeric id after reload', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  const commands = await api(page, state)
  await mount(page, 'work', 'STAFF')
  await page.getByRole('button', { name: 'Действия со столом' }).first().click()
  await page.getByRole('button', { name: 'Посадить без брони' }).click()
  await page.getByLabel('Количество гостей').fill('3')
  await page.getByLabel('Согласованное окончание (необязательно)', { exact: true }).fill('2030-01-07T20:00')
  await mount(page, 'work', 'STAFF')
  await expect(page.getByLabel('Количество гостей')).toHaveValue('3')
  await expect(page.getByLabel('Стол для гостей')).toHaveValue('1')
  await page.getByRole('button', { name: 'Подтвердить посадку' }).click()
  await expect.poll(() => commands.length).toBe(1)
  expect(commands[0]).toMatchObject({ action: 'SEAT', tableId: 1, partySize: 3 })
  expect(commands[0].bookingId).toBeUndefined()
})


test('denied command wins over a late successful private hall refresh', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  await api(page, state); await mount(page)
  await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click()
  await page.getByLabel('Имя или обозначение гостя').fill('Private pending')
  let release!: () => void
  const held = new Promise<void>(resolve => { release = resolve })
  let started = false
  await page.route('**/api/venue/1/hall', async r => { started = true; await held; await r.fulfill({ json: state }) })
  await page.getByRole('button', { name: 'Обновить доступность' }).click()
  await expect.poll(() => started).toBe(true)
  await page.route('**/api/venue/1/hall/commands', r => r.fulfill({ status: 403, json: { error: { message: 'Доступ отозван' } } }))
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click()
  await expect(page.getByText('Доступ отозван')).toBeVisible()
  const response = page.waitForResponse(r => r.url().endsWith('/api/venue/1/hall'))
  release(); await response
  await expect(page.getByText('+70000000000')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Добавить бронь', exact: true })).toHaveCount(0)
  expect(await page.evaluate(() => sessionStorage.getItem('hookah:hall:1001:1:work'))).toBeNull()
})

test('guest denied access clears its draft and cannot fall back to ordinary submission', async ({ page }) => {
  await page.route('**/api/guest/venues/1/table-availability?*', r => r.fulfill({ json: guestAvailability() }))
  await mountGuestChoice(page)
  await page.getByLabel('Комментарий', { exact: true }).fill('Private guest draft')
  await page.route('**/api/guest/venues/1/table-availability?*', r => r.fulfill({ status: 403, json: { error: { message: 'Доступ отозван' } } }))
  await page.getByRole('button', { name: 'Проверить доступные столы' }).click()
  await expect(page.getByLabel('Комментарий', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('Комментарий', { exact: true })).toBeDisabled()
  expect(await page.evaluate(() => sessionStorage.getItem('hookah:guest-booking:1003:1'))).toBeNull()
})

test('booking conflict between two clients refreshes revision preserves draft and requires explicit new attempt', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  const sent: SeatingCommand[] = []
  const second = await page.context().newPage()
  for (const client of [page, second]) {
    await api(client, state)
    await client.route('**/api/venue/1/hall/commands', async r => {
      const cmd = r.request().postDataJSON() as SeatingCommand; sent.push(cmd)
      const booking = state.bookings.find(b => b.id === cmd.id)!
      if (cmd.revision !== booking.revision) {
        await r.fulfill({ status: 409, json: { error: { code: 'SEATING_CONFLICT', message: 'Состояние изменилось. Обновите данные; введённые значения сохранены.' } } }); return
      }
      booking.revision++; booking.tableId = cmd.tableId!; booking.partySize = cmd.partySize!
      booking.scheduledAt = new Date(cmd.localStart! + '+03:00').toISOString()
      booking.agreedEndAt = new Date(cmd.localEnd! + '+03:00').toISOString()
      await r.fulfill({ json: { id: booking.id, revision: booking.revision } })
    })
    await mount(client, 'bookings')
    await client.getByRole('button', { name: 'Назначить или изменить стол' }).click()
  }
  await second.getByLabel('Начало (время заведения)', { exact: true }).fill('2030-01-07T20:30')
  await second.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T22:30')
  await second.getByLabel('Количество гостей').fill('3')
  await second.getByLabel('Стол для гостей').selectOption('3')
  await page.getByLabel('Начало (время заведения)', { exact: true }).fill('2030-01-07T21:00')
  await page.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T23:00')
  await page.getByRole('button', { name: 'Сохранить назначение' }).click()
  await expect(page.getByText('Сохранено.', { exact: true })).toBeVisible()
  const firstAccepted = structuredClone(state.bookings[0])
  await second.getByRole('button', { name: 'Сохранить назначение' }).click()
  await expect(second.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await expect(second.getByRole('button', { name: 'Сохранить назначение' })).toBeDisabled()
  expect(state.bookings[0]).toEqual(firstAccepted)
  expect(sent).toHaveLength(2)
  // Reload/dispose does not silently acknowledge a conflict or send the rejected operation again.
  await mount(second, 'bookings')
  await expect(second.getByLabel('Начало (время заведения)', { exact: true })).toHaveValue('2030-01-07T20:30')
  await expect(second.getByLabel('Согласованное окончание (необязательно)')).toHaveValue('2030-01-07T22:30')
  await expect(second.getByLabel('Количество гостей')).toHaveValue('3')
  await expect(second.getByLabel('Стол для гостей')).toHaveValue('3')
  await expect(second.getByText('Сейчас на сервере:', { exact: false })).toBeVisible()
  await second.getByRole('button', { name: 'Проверил изменения, продолжить' }).click()
  expect(sent).toHaveLength(2)
  await second.getByRole('button', { name: 'Сохранить назначение' }).click()
  await expect(second.getByText('Сохранено.', { exact: true })).toBeVisible()
  expect(sent).toHaveLength(3)
  expect(sent[2].revision).toBe(firstAccepted.revision)
  expect(sent[2].requestId).not.toBe(sent[1].requestId)
  expect(state.bookings[0]).toMatchObject({ tableId: 3, partySize: 3, scheduledAt: '2030-01-07T17:30:00.000Z', agreedEndAt: '2030-01-07T19:30:00.000Z' })
  expect(state.bookings[0].guestName).toBe(firstAccepted.guestName)
  expect(state.bookings[0].phone).toBe(firstAccepted.phone)
  await second.close()
})

test('manual conflict keeps name phone comment and scoped draft across return and account switch', async ({ page }) => {
  const state = initial(); await api(page, state)
  const sent: SeatingCommand[] = []
  await page.route('**/api/venue/1/hall/commands', async r => {
    sent.push(r.request().postDataJSON())
    if (sent.length === 1) await r.fulfill({ status: 409, json: { error: { code: 'SEATING_CONFLICT', message: 'Стол уже зарезервирован.' } } })
    else await r.fulfill({ json: { id: 50, revision: 0 } })
  })
  await mount(page)
  await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click()
  await page.getByLabel('Имя или обозначение гостя').fill('Synthetic draft')
  await page.getByLabel('Телефон (необязательно)', { exact: true }).fill('+70000000001')
  await page.getByLabel('Комментарий (необязательно)', { exact: true }).fill('Synthetic comment')
  await page.getByLabel('Начало (время заведения)', { exact: true }).fill('2030-01-07T20:00')
  await page.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T22:00')
  await page.getByLabel('Количество гостей').fill('4')
  await page.getByLabel('Стол для гостей').selectOption('2')
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click()
  await expect(page.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await mount(page, 'work', 'OWNER', 1, false, 1004)
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveCount(0)
  await mount(page, 'work', 'OWNER', 2, false)
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveCount(0)
  await mount(page, 'work', 'OWNER', 1, false)
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveValue('Synthetic draft')
  await expect(page.getByLabel('Телефон (необязательно)', { exact: true })).toHaveValue('+70000000001')
  await expect(page.getByLabel('Комментарий (необязательно)', { exact: true })).toHaveValue('Synthetic comment')
  await expect(page.getByLabel('Количество гостей')).toHaveValue('4')
  await expect(page.getByLabel('Стол для гостей')).toHaveValue('2')
  await page.getByRole('button', { name: 'Проверил изменения, продолжить' }).click()
  await page.getByLabel('Стол для гостей').selectOption('3')
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click()
  await expect(page.getByText('Сохранено.', { exact: true })).toBeVisible()
  expect(sent[1]).toMatchObject({ ...sent[0], tableId: 3, requestId: sent[1].requestId })
  expect(sent[1].requestId).not.toBe(sent[0].requestId)
})

test('refresh rebases table form only after acknowledged server change and preparation has text state', async ({ page }) => {
  const state = initial(); state.tables[0].state = 'PREPARING'; state.tables[0].reason = 'Стол освобождён. Подготовка до 20:10.'
  const commands = await api(page, state)
  await mount(page, 'settings'); await expect(page.getByText('Мест: 4 · Подготовка стола')).toBeVisible()
  await page.getByRole('button', { name: 'Редактировать стол' }).first().click()
  await page.getByLabel('Номер стола', { exact: true }).fill('7')
  state.tables[0].revision++; state.tables[0].capacity = 8
  await page.getByRole('button', { name: 'Обновить доступность' }).click()
  await expect(page.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await expect(page.getByLabel('Номер стола', { exact: true })).toHaveValue('7')
  await expect(page.getByLabel('Вместимость (пусто — неизвестна)')).toHaveValue('8')
  await expect(page.getByRole('button', { name: 'Сохранить стол' })).toBeDisabled()
  expect(commands).toHaveLength(0)
  await page.getByRole('button', { name: 'Проверил изменения, продолжить' }).click()
  await page.getByRole('button', { name: 'Сохранить стол' }).click()
  await expect.poll(() => commands.length).toBe(1)
  expect(commands[0]).toMatchObject({ revision: state.tables[0].revision, number: 7, capacity: 8 })
})

test('late conflict refresh cannot restore disposed account and revoked refresh clears conflict draft', async ({ page }) => {
  const state = initial(); await api(page, state); await mount(page)
  await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click()
  await page.getByLabel('Имя или обозначение гостя').fill('Synthetic private')
  await page.route('**/api/venue/1/hall/commands', r => r.fulfill({ status: 409, json: { error: { message: 'Конфликт' } } }))
  let entered!: () => void; let release!: () => void
  const started = new Promise<void>(r => { entered = r }); const gate = new Promise<void>(r => { release = r })
  await page.route('**/api/venue/1/hall', async r => { entered(); await gate; await r.fulfill({ json: state }).catch(() => {}) })
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click(); await started
  await page.unroute('**/api/venue/1/hall')
  await page.route('**/api/venue/1/hall', r => r.fulfill({ json: state }))
  await mount(page, 'work', 'OWNER', 1, false, 1004)
  release()
  await expect(page.getByText('Synthetic private', { exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Проверил изменения, продолжить' })).toHaveCount(0)
  await page.route('**/api/venue/1/hall', r => r.fulfill({ status: 403, json: { error: { message: 'Доступ отозван' } } }))
  await mount(page, 'work', 'OWNER', 1, false, 1001)
  await expect(page.getByText('Доступ отозван')).toBeVisible()
  expect(await page.evaluate(() => sessionStorage.getItem('hookah:hall:1001:1:work'))).toBeNull()
})

test('hall conflict draft survives real Venue router Telegram BackButton and reload', async ({ page }) => {
  const state = initial()
  await page.addInitScript(() => {
    Object.assign(window, { Telegram: { WebApp: { initDataUnsafe: { user: { id: 1001 } }, ready() {}, expand() {} } } })
    sessionStorage.setItem(`hookah_session_token:${location.host}:user:1001`, JSON.stringify({ token: 'synthetic', expiresAtEpochSeconds: Math.floor(Date.now() / 1000) + 3600 }))
  })
  await page.route('**/miniapp/hall-app-test*', r => r.fulfill({ contentType: 'text/html', body: '<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/miniapp/src/style.css"></head><body><div id="app"></div><script type="module">import {mountVenueApp} from "/miniapp/src/screens/venueApp.ts";mountVenueApp({root:document.querySelector("#app"),backendUrl:location.origin});</script></body></html>' }))
  let posts = 0
  await page.route('**/api/**', r => {
    const path = new URL(r.request().url()).pathname
    if (!path.startsWith('/api/')) return r.fallback()
    if (path === '/api/venue/me') return r.fulfill({ json: { userId: 1001, venues: [{ venueId: 1, venueName: 'Synthetic', venueStatus: 'PUBLISHED', role: 'OWNER', permissions: ['TABLE_VIEW', 'TABLE_MANAGE', 'BOOKING_VIEW'] }] } })
    if (path === '/api/venue/1/hall') return r.fulfill({ json: state })
    if (path === '/api/venue/1/hall/commands') { posts++; return r.fulfill({ status: 409, json: { error: { message: 'Стол зарезервирован.' } } }) }
    return r.fulfill({ json: { unreadCount: 0, calls: [], items: [], linked: false } })
  })
  await page.goto('hall-app-test?venueId=1#/hall')
  await page.getByRole('button', { name: 'Добавить бронь', exact: true }).click()
  await page.getByLabel('Имя или обозначение гостя').fill('BackButton draft')
  await page.getByRole('button', { name: 'Создать подтверждённую бронь' }).click()
  await expect(page.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await page.evaluate(() => window.dispatchEvent(new Event('hookah:e2e-telegram-back')))
  await expect(page).not.toHaveURL(/#\/hall$/)
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveCount(0)
  await page.evaluate(() => { location.hash = '#/hall' })
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveValue('BackButton draft')
  await expect(page.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await page.reload()
  await expect(page.getByLabel('Имя или обозначение гостя')).toHaveValue('BackButton draft')
  await expect(page.getByRole('button', { name: 'Создать подтверждённую бронь' })).toBeDisabled()
  expect(posts).toBe(1)
})

for (const source of ['STAFF', 'GUEST'] as const) test(`stale ${source} booking SEAT preserves draft and requires explicit revision confirmation`, async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  state.bookings[0].source = source
  const sent: SeatingCommand[] = []
  const second = await page.context().newPage()
  for (const client of [page, second]) {
    await api(client, state)
    await client.route('**/api/venue/1/hall/commands', async r => {
      const command = r.request().postDataJSON() as SeatingCommand; sent.push(command)
      const booking = state.bookings[0]
      if (command.revision !== booking.revision) {
        await r.fulfill({ status: 409, json: { error: { code: 'SEATING_CONFLICT', message: 'Состояние изменилось. Обновите данные; введённые значения сохранены.' } } }); return
      }
      booking.revision++
      if (command.action === 'SEAT') booking.status = 'SEATED'
      else booking.agreedEndAt = new Date(command.localEnd! + '+03:00').toISOString()
      await r.fulfill({ json: { id: booking.id, revision: booking.revision } })
    })
    await mount(client, 'bookings')
  }
  await second.getByRole('button', { name: 'Посадить по брони', exact: true }).click()
  await expect(second.getByLabel('Количество гостей')).toHaveAttribute('readonly', '')
  await expect(second.getByLabel('Количество гостей')).toHaveValue('2')
  await second.getByLabel('Стол для гостей').selectOption('3')
  await expect(second.getByLabel('Согласованное окончание (необязательно)')).toHaveAttribute('readonly', '')
  await page.getByRole('button', { name: 'Назначить или изменить стол' }).click()
  await page.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T23:00')
  await page.getByRole('button', { name: 'Сохранить назначение' }).click()
  await expect(page.getByText('Сохранено.', { exact: true })).toBeVisible()
  await expect(page.getByText(/уведомление доставлено|сообщение доставлено/i)).toHaveCount(0)
  const revision = state.bookings[0].revision
  await second.getByRole('button', { name: 'Подтвердить посадку' }).click()
  await expect(second.getByRole('button', { name: 'Проверил изменения, продолжить' })).toBeVisible()
  await expect(second.getByRole('button', { name: 'Подтвердить посадку' })).toBeDisabled()
  expect(state.bookings[0].status).toBe('CONFIRMED')
  await mount(second, 'bookings')
  await expect(second.getByLabel('Количество гостей')).toHaveValue('2')
  await expect(second.getByLabel('Стол для гостей')).toHaveValue('3')
  await expect(second.getByLabel('Согласованное окончание (необязательно)')).toHaveValue('2030-01-07T23:00')
  expect(sent).toHaveLength(2)
  await second.getByRole('button', { name: 'Проверил изменения, продолжить' }).click()
  expect(sent).toHaveLength(2)
  await second.getByRole('button', { name: 'Подтвердить посадку' }).click()
  await expect(second.getByText('Сохранено.', { exact: true })).toBeVisible()
  expect(sent[2]).toMatchObject({ action: 'SEAT', bookingId: state.bookings[0].id, revision, tableId: 3 })
  expect(sent[2].partySize).toBeUndefined()
  expect(sent[2].localEnd).toBeUndefined()
  expect(sent[2].requestId).not.toBe(sent[1].requestId)
  expect(state.bookings[0].status).toBe('SEATED')
  await second.close()
})


test('booking SEAT shows authoritative read only terms and STAFF cannot change the agreement', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  const commands = await api(page, state)
  await mount(page, 'bookings', 'STAFF')
  await page.getByRole('button', { name: 'Посадить по брони', exact: true }).click()
  await expect(page.getByLabel('Количество гостей')).toHaveValue('2')
  await expect(page.getByLabel('Количество гостей')).toHaveAttribute('readonly', '')
  await expect(page.getByLabel('Согласованное окончание (необязательно)')).toHaveValue('2030-01-07T22:00')
  await expect(page.getByLabel('Согласованное окончание (необязательно)')).toHaveAttribute('readonly', '')
  await expect(page.getByText(/Посадка сохраняет условия брони: гостей 2/)).toBeVisible()
  await expect(page.getByText(/Изменить число гостей или окончание до посадки может владелец/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Изменить условия брони' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Подтвердить посадку' }).click()
  expect(commands[0]).toMatchObject({ action: 'SEAT', bookingId: 1, revision: 0 })
  expect(commands[0].partySize).toBeUndefined()
  expect(commands[0].localEnd).toBeUndefined()
})

test('walk in SEAT keeps editable terms and manager changes booking through assignment', async ({ page }) => {
  const state = structuredClone(wire.booked) as HallState
  const commands = await api(page, state)
  await mount(page, 'bookings', 'MANAGER')
  await page.getByRole('button', { name: 'Посадить по брони', exact: true }).click()
  await page.getByRole('button', { name: 'Изменить условия брони' }).click()
  await expect(page.getByLabel('Количество гостей')).not.toHaveAttribute('readonly', '')
  await page.getByLabel('Количество гостей').fill('3')
  await page.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T21:00')
  await page.getByRole('button', { name: 'Сохранить назначение' }).click()
  expect(commands[0]).toMatchObject({ action: 'ASSIGN_BOOKING', partySize: 3, localEnd: '2030-01-07T21:00' })
  await mount(page, 'work', 'STAFF')
  await page.getByRole('button', { name: 'Действия со столом' }).first().click()
  await page.getByRole('button', { name: 'Посадить без брони' }).click()
  await expect(page.getByLabel('Количество гостей')).not.toHaveAttribute('readonly', '')
  await page.getByLabel('Количество гостей').fill('3')
  await page.getByLabel('Согласованное окончание (необязательно)').fill('2030-01-07T21:00')
  await page.getByLabel('Гости предупреждены о сокращённом времени').check()
  await page.getByRole('button', { name: 'Подтвердить посадку' }).click()
  expect(commands[1]).toMatchObject({ action: 'SEAT', partySize: 3, localEnd: '2030-01-07T21:00', shorterVisitAcknowledged: true })
  expect(commands[1].bookingId).toBeUndefined()
})
