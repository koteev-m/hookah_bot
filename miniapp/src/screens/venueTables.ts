import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateTablesBatch,
  venueExportQrPackage,
  venueGetTables,
  venueRotateTableToken,
  venueRotateTableTokens
} from '../shared/api/venueApi'
import type { VenueAccessDto, VenueTableDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueTablesOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type TablesRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  countInput: HTMLInputElement
  startInput: HTMLInputElement
  createButton: HTMLButtonElement
  rotateAllButton: HTMLButtonElement
  exportSelect: HTMLSelectElement
  exportButton: HTMLButtonElement
  list: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function renderErrorActions(container: HTMLElement, actions: ApiErrorAction[]) {
  container.replaceChildren()
  actions.forEach((action) => {
    const button = document.createElement('button')
    button.textContent = action.label
    if (action.kind === 'secondary') {
      button.classList.add('button-secondary')
    }
    button.addEventListener('click', action.onClick)
    container.appendChild(button)
  })
}

function buildTablesDom(root: HTMLDivElement): TablesRefs {
  const wrapper = el('div', { className: 'venue-tables' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Столы и QR' })
  const form = el('div', { className: 'venue-form-grid' })
  const countInput = document.createElement('input')
  countInput.className = 'venue-input'
  countInput.type = 'number'
  countInput.min = '1'
  countInput.placeholder = 'Количество столов'
  const startInput = document.createElement('input')
  startInput.className = 'venue-input'
  startInput.type = 'number'
  startInput.min = '1'
  startInput.placeholder = 'Стартовый номер (опц)'
  const createButton = el('button', { text: 'Создать столы' }) as HTMLButtonElement
  append(form, countInput, startInput, createButton)

  const rotateAllButton = el('button', { className: 'button-secondary', text: 'Переиздать токены (все)' }) as HTMLButtonElement

  const exportRow = el('div', { className: 'venue-form-row' })
  const exportSelect = document.createElement('select')
  exportSelect.className = 'venue-select'
  exportSelect.appendChild(new Option('ZIP', 'zip'))
  exportSelect.appendChild(new Option('PDF', 'pdf'))
  const exportButton = el('button', { className: 'button-secondary', text: 'Скачать QR пакет' }) as HTMLButtonElement
  append(exportRow, exportSelect, exportButton)

  append(header, title, form, rotateAllButton, exportRow)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-table-list' })

  append(wrapper, header, status, error, list)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    countInput,
    startInput,
    createButton,
    rotateAllButton,
    exportSelect,
    exportButton,
    list
  }
}

function renderTableRow(
  table: VenueTableDto,
  canRotate: boolean,
  onRotate: (tableId: number) => void
) {
  const row = el('div', { className: 'venue-table-row' })
  const info = el('div', { className: 'venue-table-info' })
  append(
    info,
    el('strong', { text: table.tableLabel || `Стол №${table.tableNumber}` }),
    el('p', { className: 'venue-order-sub', text: `Token: ${table.activeTokenIssuedAt ? new Date(table.activeTokenIssuedAt).toLocaleString() : '—'}` })
  )

  const actions = el('div', { className: 'venue-table-actions' })
  const rotateButton = el('button', { className: 'button-small', text: 'Переиздать токен' }) as HTMLButtonElement
  rotateButton.disabled = !canRotate
  rotateButton.title = canRotate ? '' : 'Недостаточно прав'
  rotateButton.addEventListener('click', () => onRotate(table.tableId))
  append(actions, rotateButton)

  append(row, info, actions)
  return row
}

export function renderVenueTablesScreen(options: VenueTablesOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildTablesDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0

  const canView = access.permissions.includes('TABLE_VIEW')
  const canManage = access.permissions.includes('TABLE_MANAGE')
  const canRotate = access.permissions.includes('TABLE_TOKEN_ROTATE')
  const canRotateAll = access.permissions.includes('TABLE_TOKEN_ROTATE_ALL')
  const canExport = access.permissions.includes('TABLE_QR_EXPORT')

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
  }

  const showError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug, scope: 'venue' })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    const actions: ApiErrorAction[] = presentation.actions.length
      ? presentation.actions.map((action) =>
          action.label === 'Повторить' ? { ...action, onClick: () => void loadTables() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadTables() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const renderTables = (tables: VenueTableDto[]) => {
    refs.list.replaceChildren()
    if (!tables.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Столы отсутствуют.' }))
      return
    }
    tables.forEach((table) => {
      refs.list.appendChild(renderTableRow(table, canRotate, (tableId) => void rotateToken(tableId)))
    })
  }

  const loadTables = async () => {
    if (!canView) {
      refs.list.replaceChildren(el('p', { className: 'venue-empty', text: 'Недостаточно прав для просмотра столов.' }))
      return
    }
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetTables(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    renderTables(result.data.tables)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const createTables = async () => {
    if (!canManage) {
      showToast('Недостаточно прав')
      return
    }
    const count = Number.parseInt(refs.countInput.value, 10)
    if (!Number.isFinite(count) || count <= 0) {
      showToast('Введите количество')
      return
    }
    const startNumberRaw = refs.startInput.value.trim()
    let startNumber: number | undefined
    if (startNumberRaw) {
      const parsedStartNumber = Number.parseInt(startNumberRaw, 10)
      if (!Number.isFinite(parsedStartNumber) || parsedStartNumber <= 0) {
        showToast('Введите корректный стартовый номер')
        return
      }
      startNumber = parsedStartNumber
    }
    const result = await venueCreateTablesBatch(
      backendUrl,
      { venueId, body: { count, startNumber } },
      deps
    )
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast(`Создано столов: ${result.data.count}`)
    refs.countInput.value = ''
    refs.startInput.value = ''
    void loadTables()
  }

  const rotateToken = async (tableId: number) => {
    if (!canRotate) {
      showToast('Недостаточно прав')
      return
    }
    const result = await venueRotateTableToken(backendUrl, { venueId, tableId }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Токен обновлён')
    void loadTables()
  }

  const rotateAllTokens = async () => {
    if (!canRotateAll) {
      showToast('Недостаточно прав')
      return
    }
    const result = await venueRotateTableTokens(backendUrl, { venueId, body: {} }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast(`Переиздано токенов: ${result.data.rotatedCount}`)
    void loadTables()
  }

  const downloadQr = async () => {
    if (!canExport) {
      showToast('Недостаточно прав')
      return
    }
    const format = refs.exportSelect.value as 'zip' | 'pdf'
    const result = await venueExportQrPackage(backendUrl, { venueId, format }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    const blobUrl = URL.createObjectURL(result.data.blob)
    const link = document.createElement('a')
    link.href = blobUrl
    const fallbackName = `venue-${venueId}-qr.${format}`
    link.download = result.data.fileName ?? fallbackName
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(blobUrl)
    showToast('QR пакет скачан')
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.createButton, 'click', () => void createTables()))
  disposables.push(on(refs.rotateAllButton, 'click', () => void rotateAllTokens()))
  disposables.push(on(refs.exportButton, 'click', () => void downloadQr()))

  refs.createButton.disabled = !canManage
  refs.createButton.title = canManage ? '' : 'Недостаточно прав'
  refs.rotateAllButton.disabled = !canRotateAll
  refs.rotateAllButton.title = canRotateAll ? '' : 'Недостаточно прав'
  refs.exportButton.disabled = !canExport
  refs.exportButton.title = canExport ? '' : 'Недостаточно прав'

  void loadTables()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
