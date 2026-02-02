import { REQUEST_ABORTED_CODE } from '../api/abort'
import type { RequestDependencies } from '../api/request'
import { normalizeErrorCode } from '../api/errorMapping'
import { ApiErrorCodes, type ApiErrorInfo } from '../api/types'
import type { NightTablesResponse } from '../api/managerDtos'
import { getTablesForNight } from '../api/managerApi'

export type NightTablesStatus = 'idle' | 'loading' | 'ready' | 'error' | 'unauthorized'

export type NightTablesState = {
  status: NightTablesStatus
  data: NightTablesResponse | null
  error: ApiErrorInfo | null
  errorMessage: string
  canRetry: boolean
}

export type NightTablesHook = {
  getState: () => NightTablesState
  subscribe: (listener: (state: NightTablesState) => void) => () => void
  setParams: (clubId?: number, nightKey?: string) => void
  reload: () => Promise<void>
  abort: () => void
}

const defaultState: NightTablesState = {
  status: 'idle',
  data: null,
  error: null,
  errorMessage: '',
  canRetry: false
}

function isRetryable(error: ApiErrorInfo) {
  return error.status === 0 || error.status >= 500
}

export function useNightTables(
  backendUrl: string,
  deps: RequestDependencies,
  clubId?: number,
  nightKey?: string
): NightTablesHook {
  let state: NightTablesState = { ...defaultState }
  let currentClubId = clubId
  let currentNightKey = nightKey
  let requestId = 0
  let controller: AbortController | null = null
  const listeners = new Set<(next: NightTablesState) => void>()

  const emit = () => {
    listeners.forEach((listener) => listener(state))
  }

  const setState = (next: NightTablesState) => {
    state = next
    emit()
  }

  const setParams = (nextClubId?: number, nextNightKey?: string) => {
    currentClubId = nextClubId
    currentNightKey = nextNightKey
  }

  const abort = () => {
    if (controller) {
      controller.abort()
      controller = null
    }
  }

  const reload = async () => {
    if (!currentClubId || !currentNightKey) {
      setState({ ...defaultState })
      return
    }
    abort()
    const activeRequest = ++requestId
    controller = new AbortController()
    setState({
      status: 'loading',
      data: state.data,
      error: null,
      errorMessage: '',
      canRetry: false
    })

    const result = await getTablesForNight(
      backendUrl,
      currentClubId,
      currentNightKey,
      deps,
      controller.signal
    )
    if (requestId !== activeRequest) {
      return
    }
    controller = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      return
    }
    if (!result.ok) {
      const normalized = normalizeErrorCode(result.error)
      if (normalized === ApiErrorCodes.UNAUTHORIZED) {
        setState({
          status: 'unauthorized',
          data: null,
          error: result.error,
          errorMessage: 'Требуется повторная авторизация.',
          canRetry: false
        })
        return
      }
      setState({
        status: 'error',
        data: null,
        error: result.error,
        errorMessage: result.error.message ?? 'Не удалось загрузить столы.',
        canRetry: isRetryable(result.error)
      })
      return
    }

    setState({
      status: 'ready',
      data: result.data,
      error: null,
      errorMessage: '',
      canRetry: false
    })
  }

  const subscribe = (listener: (next: NightTablesState) => void) => {
    listeners.add(listener)
    listener(state)
    return () => {
      listeners.delete(listener)
    }
  }

  return {
    getState: () => state,
    subscribe,
    setParams,
    reload,
    abort
  }
}
