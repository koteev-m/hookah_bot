import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { expect, test } from '@playwright/test'
import { ApiErrorCodes, type ApiResult } from '../src/shared/api/types'
import type { TableResolveResponse, TableRestoreResponse } from '../src/shared/api/guestDtos'

type TableContextModule = typeof import('../src/shared/state/tableContext')
type Pending<T> = { signal: AbortSignal; complete: (result: ApiResult<T>) => void }

const table = {
  venueId: 1, venueName: 'Микс', tableId: 7, tableSessionId: 70,
  tableSessionStatus: 'ACTIVE', tableSessionActive: true, tableNumber: '4',
  venueStatus: 'PUBLISHED', subscriptionStatus: 'ACTIVE', available: true,
  unavailableReason: null
}

function createContext() {
  const resolves: Pending<TableResolveResponse>[] = []
  const restores: Pending<TableRestoreResponse>[] = []
  const remembered: string[] = []
  const selectedTabs: Array<[number, number | null]> = []
  const storage = new Map<string, string>()
  const telegram = { tableTokenStatus: 'missing', tableToken: null as string | null, tableSessionId: null, telegramUserId: 123, tableTokenAutoResolve: false }
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
    './guestTabSelection': { setSelectedGuestTabId: (session: number, tab: number | null) => { selectedTabs.push([session, tab]) } }
  }
  const source = readFileSync(new URL('../src/shared/state/tableContext.ts', import.meta.url), 'utf8')
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const module = { exports: {} as TableContextModule }
  runInNewContext(output, {
    exports: module.exports, AbortController,
    window: { sessionStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key)
    } },
    require: (name: string) => {
      if (!(name in deps)) throw new Error(`Unexpected dependency: ${name}`)
      return deps[name]
    }
  })
  return { context: module.exports, resolves, restores, remembered, selectedTabs, storage, telegram }
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
    expect(h.context.getTableContext().status).toBe('resolving')
    const scan = h.context.refresh({ scannedToken: 'synthetic-revoked' })
    expect(h.restores[0].signal.aborted).toBe(true)
    h.resolves[0].complete(failure)
    expect((await scan)?.orderAllowed).toBe(false)
    expect(h.context.getTableContext().status).toBe('missing')
    expect((await h.context.ensureResolved()).status).toBe('missing')
    h.restores[0].complete({ ok: true, data: { context: { ...table, tableToken: 'synthetic-old', tabId: 71 } } })
    await Promise.resolve()
    expect(h.context.getTableContext().status).toBe('missing')
    expect(h.remembered).toEqual([])
    expect(h.selectedTabs).toEqual([])
    expect(h.storage.size).toBe(0)
    const retry = h.context.refresh({ scannedToken: 'synthetic-valid' })
    h.resolves[1].complete({ ok: true, data: table })
    expect((await retry)?.tableSessionId).toBe(70)
    expect(h.context.getTableContext().orderAllowed).toBe(true)
  })

  for (const interruptRefresh of [false, true]) {
    test(`table context rejected scan ${name} preserves ready context${interruptRefresh ? ' through an interrupted refresh' : ''}`, async () => {
      const h = createContext()
      h.context.initTableContext()
      h.restores[0].complete({ ok: true, data: { context: { ...table, tableToken: 'synthetic-current', tabId: 71 } } })
      await h.context.ensureResolved()
      const before = h.context.getTableContext()
      const stored = [...h.storage]
      const tabs = [...h.selectedTabs]
      let refresh: ReturnType<TableContextModule['refresh']> | null = null
      if (interruptRefresh) {
        refresh = h.context.refresh()
        expect(h.context.getTableContext().status).toBe('resolving')
      }
      const scan = h.context.refresh({ scannedToken: 'synthetic-rejected' })
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
  const scan = h.context.refresh({ scannedToken: 'synthetic-valid' })
  h.context.clearCurrentTableContext()
  h.restores[0].complete({ ok: true, data: { context: { ...table, tableToken: 'synthetic-old', tabId: 71 } } })
  h.resolves[0].complete({ ok: true, data: table })
  expect(await scan).toBeNull()
  expect((await h.context.ensureResolved()).status).toBe('missing')
  expect(h.remembered).toEqual([])
  expect(h.selectedTabs).toEqual([])
  expect(h.storage.size).toBe(0)
})
