import { readFileSync } from 'node:fs'
import { webcrypto } from 'node:crypto'
import { setImmediate as dispatched } from 'node:timers/promises'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { expect, test } from '@playwright/test'
import { normalizeTableToken } from '../src/shared/validation/tableToken'
import { ApiErrorCodes, type ApiResult } from '../src/shared/api/types'
import type { TableResolveResponse, TableRestoreResponse } from '../src/shared/api/guestDtos'

import type { VisitRecord } from '../src/shared/state/visitStorage'

type TableContextModule = typeof import('../src/shared/state/tableContext')
type Pending<T> = { signal: AbortSignal; complete: (result: ApiResult<T>) => void }

const table = {
  venueId: 1, venueName: 'Микс', tableId: 7, tableSessionId: 70,
  tableSessionStatus: 'ACTIVE', visitProtocol: 1, visitGeneration: '0', tableSessionActive: true, tableNumber: '4',
  venueStatus: 'PUBLISHED', subscriptionStatus: 'ACTIVE', available: true,
  unavailableReason: null
}

function createContext(records = new Map<string, VisitRecord>()) {
  const resolves: Pending<TableResolveResponse>[] = []
  const restores: Pending<TableRestoreResponse>[] = []
  const remembered: string[] = []
  const selectedTabs: Array<[number, number | null]> = []
  const storage = new Map<string, string>()
  const history = { state: null as { hookahGuestNavigation: object } | null }
  const telegram = { tableTokenStatus: 'missing', tableToken: null as string | null, tableSessionId: null as number | null,
    telegramUserId: 123, tableTokenAutoResolve: false, tableTokenFromUrl: false }
  const store = (data: Map<string, string>) => ({
    getItem: (key: string) => data.get(key) ?? null,
    setItem: (key: string, value: string) => data.set(key, value),
    removeItem: (key: string) => data.delete(key)
  })
  const deps: Record<string, unknown> = {
    '../api/backend': { getBackendBaseUrl: () => 'http://synthetic.invalid' },
    '../api/abort': { REQUEST_ABORTED_CODE: ApiErrorCodes.REQUEST_ABORTED },
    '../api/auth': { getAccessToken: () => 'synthetic', clearSession: () => {} },
    '../api/guestApi': {
      guestResolveTable: (_url: string, _token: string, _deps: unknown, signal: AbortSignal) => new Promise((complete) => resolves.push({ signal, complete })),
      guestRestoreTable: (_url: string, _deps: unknown, signal: AbortSignal) => new Promise((complete) => restores.push({ signal, complete }))
    },
    '../api/types': { ApiErrorCodes },
    '../debug': { isDebugEnabled: () => false },
    '../telegram': {
      getTelegramContext: () => telegram,
      rememberTableToken: (token: string) => { remembered.push(token) },
      forgetTableToken: () => {}
    },
    '../validation/tableToken': { normalizeTableToken },
    './cartStore': { cartRequiresTabChoice: () => false, discardTableCart: () => true },
    './guestTabSelection': {
      setSelectedGuestTabId: (session: number, tab: number | null) => { selectedTabs.push([session, tab]) },
      bindGuestTabParticipation: () => {}, restoreGuestTabSelection: (_session: number, _hasDraft: boolean) => {}
    }
  }
  const window = { addEventListener() {}, history, sessionStorage: store(storage) }
  function loadStateModule(name: string) {
    const source = readFileSync(new URL(`../src/shared/state/${name}.ts`, import.meta.url), 'utf8')
    const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    const exports = {}
    runInNewContext(output, { exports, window, performance, crypto: webcrypto, AbortController, require: (name: string) => {
      if (!(name in deps)) throw new Error(`Unexpected dependency: ${name}`)
      return deps[name]
    } })
    return exports
  }
  deps['./visitStorage'] = {
    ...loadStateModule('visitStorage'),
    visitStorageReady: () => true,
    withVisitRecord: async (key: string, transition: (record: VisitRecord) => unknown) => {
      const record = structuredClone(records.get(key) ?? { pointer: null, revision: null, pending: null, exits: [], cleanup: [] })
      const result = transition(record)
      records.set(key, record)
      return result
    }
  }
  deps['./visitSelection'] = loadStateModule('visitSelection')
  const context = loadStateModule('tableContext') as TableContextModule
  return { context, resolves, restores, remembered, selectedTabs, storage, records, history, telegram }
}

const failures: Array<[string, ApiResult<TableResolveResponse>]> = [
  ['404', { ok: false, error: { status: 404, code: ApiErrorCodes.NOT_FOUND } }],
  ['403', { ok: false, error: { status: 403, code: ApiErrorCodes.FORBIDDEN } }],
  ['500', { ok: false, error: { status: 500, code: ApiErrorCodes.INTERNAL_ERROR } }],
  ['network', { ok: false, error: { status: 0, code: ApiErrorCodes.NETWORK_ERROR } }],
  ['unavailable', { ok: true, data: { ...table, available: false, unavailableReason: 'SERVICE_SUSPENDED' } }],
  ['ended', { ok: true, data: { ...table, tableSessionActive: false, tableSessionStatus: 'ENDED' } }]
]

for (const [name, failure] of failures) {
  test(`table context rejected scan ${name} finishes a superseded slow restore and permits another scan`, async () => {
    const h = createContext()
    h.context.initTableContext()
    await dispatched()
    expect(h.context.getTableContext().status).toBe('resolving')
    const scan = h.context.refresh({ scannedToken: 'synthetic-revoked' })
    await dispatched()
    expect(h.restores[0].signal.aborted).toBe(true)
    h.resolves[0].complete(failure)
    expect((await scan)?.orderAllowed).toBe(false)
    expect(h.context.getTableContext().status).toBe('missing')
    expect((await h.context.ensureResolved()).status).toBe('missing')
    h.restores[0].complete({ ok: true, data: { visitProtocol: 1, context: { ...table, tableToken: 'synthetic-old', tabId: 71 } } })
    await Promise.resolve()
    expect(h.context.getTableContext().status).toBe('missing')
    expect(h.remembered).toEqual([])
    expect(h.selectedTabs).toEqual([])
    expect(h.storage.size).toBe(0)
    const retry = h.context.refresh({ scannedToken: 'synthetic-valid' })
    await dispatched()
    h.resolves[1].complete({ ok: true, data: table })
    expect((await retry)?.tableSessionId).toBe(70)
    expect(h.context.getTableContext().orderAllowed).toBe(true)
  })

  for (const interruptRefresh of [false, true]) {
    test(`table context rejected scan ${name} preserves ready context${interruptRefresh ? ' through an interrupted refresh' : ''}`, async () => {
      const h = createContext()
      h.context.initTableContext()
      await dispatched()
      h.restores[0].complete({ ok: true, data: { visitProtocol: 1, context: { ...table, tableToken: 'synthetic-current', tabId: 71 } } })
      await h.context.ensureResolved()
      const before = h.context.getTableContext()
      const stored = [...h.storage]
      const tabs = [...h.selectedTabs]
      let refresh: ReturnType<TableContextModule['refresh']> | null = null
      if (interruptRefresh) {
        refresh = h.context.refresh()
        await dispatched()
        expect(h.context.getTableContext().status).toBe('resolving')
      }
      const scan = h.context.refresh({ scannedToken: 'synthetic-rejected' })
      await dispatched()
      h.resolves.at(-1)!.complete(failure)
      expect((await scan)?.orderAllowed).toBe(false)
      expect(h.context.getTableContext()).toEqual(before)
      expect([...h.storage]).toEqual(stored)
      expect(h.selectedTabs).toEqual(tabs)
      if (refresh) {
        h.resolves[0].complete({ ok: true, data: { ...table, tableSessionId: 999 } })
        await refresh
        expect(h.context.getTableContext()).toEqual(before)
      }
    })
  }
}

test('table context late restore and scan responses cannot restore a visit after explicit exit', async () => {
  const h = createContext()
  h.context.initTableContext()
  await dispatched()
  const scan = h.context.refresh({ scannedToken: 'synthetic-valid' })
  await dispatched()
  h.context.clearCurrentTableContext()
  h.restores[0].complete({ ok: true, data: { visitProtocol: 1, context: { ...table, tableToken: 'synthetic-old', tabId: 71 } } })
  h.resolves[0].complete({ ok: true, data: table })
  expect(await scan).toBeNull()
  expect((await h.context.ensureResolved()).status).toBe('missing')
  expect(h.remembered).toEqual([])
  expect(h.selectedTabs).toEqual([])
  expect(h.storage.size).toBe(0)
})

test('table context cancelling pending QR keeps the confirmed visit and permits a later scan', async () => {
  const h = createContext()
  h.context.initTableContext()
  await dispatched()
  h.restores[0].complete({ ok: true, data: { visitProtocol: 1, context: { ...table, tableToken: 'synthetic-current', tabId: 71 } } })
  await h.context.ensureResolved()
  const before = h.context.getTableContext()
  const stored = [...h.storage]
  const scan = h.context.refresh({ scannedToken: 'synthetic-pending' })
  await dispatched()
  h.context.cancelPendingTableRefresh()
  expect(h.resolves[0].signal.aborted).toBe(true)
  h.resolves[0].complete({ ok: true, data: { ...table, tableSessionId: 999 } })
  expect(await scan).toBeNull()
  expect(h.context.getTableContext()).toEqual(before)
  expect([...h.storage]).toEqual(stored)
  const retry = h.context.refresh({ scannedToken: 'synthetic-next' })
  await dispatched()
  h.resolves[1].complete({ ok: true, data: { ...table, tableSessionId: 72 } })
  expect((await retry)?.tableSessionId).toBe(72)
})

const visitKey = 'hookah_guest_table_session_context:user:123'
const visitA = JSON.stringify({ tableToken: 'synthetic-a', tableSessionId: 61 })
const visitC = JSON.stringify({ tableToken: 'synthetic-c', tableSessionId: 81 })

async function failedBotLink() {
  const h = createContext()
  h.storage.set(visitKey, visitA)
  h.records.set(visitKey, { pointer: JSON.parse(visitA), revision: null, pending: null, exits: [], cleanup: [] })
  Object.assign(h.telegram, { tableToken: 'synthetic-b', tableSessionId: 70, tableTokenStatus: 'valid',
    tableTokenFromUrl: true, tableTokenAutoResolve: true })
  h.context.initTableContext()
  await dispatched()
  h.history.state = { hookahGuestNavigation: {} }
  await dispatched()
  h.resolves[0].complete({ ok: false, error: { status: 503 } })
  expect((await h.context.ensureResolved()).status).toBe('error')
  expect([h.storage.get(visitKey), JSON.stringify(h.records.get(visitKey)?.pointer)]).toEqual([visitA, visitA])
  return h
}

for (const event of ['cancel', 'account switch', 'different link'] as const) {
  test(`Bot link retry is discarded before dispatch on ${event}`, async () => {
    const h = await failedBotLink()
    if (event === 'cancel') h.context.cancelPendingTableRefresh()
    if (event === 'account switch') h.telegram.telegramUserId = 456
    if (event === 'different link') h.telegram.tableSessionId = 81
    h.context.retryTableLoad()
    expect(h.resolves).toHaveLength(1)
    expect(h.restores).toHaveLength(0)
    expect(h.storage.get(visitKey)).toBe(visitA)
    expect(JSON.stringify(h.records.get(visitKey)?.pointer)).toBe(visitA)
  })
}

for (const event of ['cancel', 'account switch', 'new scan', 'new selection in another window', 'mismatched response'] as const) {
  test(`late Bot link retry cannot replace the chosen visit after ${event}`, async () => {
    const h = await failedBotLink()
    h.context.retryTableLoad()
    const retry = h.context.ensureResolved()
    await dispatched()
    if (event === 'cancel') h.context.cancelPendingTableRefresh()
    if (event === 'account switch') h.telegram.telegramUserId = 456
    if (event === 'new selection in another window') {
      const other = createContext(h.records)
      const scan = other.context.refresh({ scannedToken: 'synthetic-c' })
      await dispatched()
      other.resolves[0].complete({ ok: true, data: { ...table, tableSessionId: 81 } })
      await scan
    }
    if (event === 'new scan') {
      const scan = h.context.refresh({ scannedToken: 'synthetic-c' })
      await dispatched()
      h.resolves[2].complete({ ok: true, data: { ...table, tableSessionId: 81 } })
      await scan
    }
    h.resolves[1].complete({ ok: true, data: { ...table, tableSessionId: event === 'mismatched response' ? 99 : 70 } })
    await retry
    expect(h.storage.get(visitKey)).toBe(event === 'new scan' ? visitC : visitA)
    expect(JSON.stringify(h.records.get(visitKey)?.pointer)).toBe(event === 'new scan' || event === 'new selection in another window' ? visitC : visitA)
  })
}
