import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateTablesBatch,
  venueExportQrPackage,
  venueGetTables,
  venueSendQrToTelegram,
  venueRotateTableToken,
  venueRotateTableTokens
} from '../shared/api/venueApi'
import type { VenueAccessDto, VenueTableDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
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
  numbers: HTMLDivElement
  validation: HTMLParagraphElement
  exportRow: HTMLDivElement
  exportButton: HTMLButtonElement
  zipButton: HTMLButtonElement
  sendButton: HTMLButtonElement
  downloadLink: HTMLAnchorElement
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

function buildTablesDom(root: HTMLDivElement, isTelegram: boolean): TablesRefs {
  const wrapper = el('div', { className: 'venue-tables' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Столы и QR' })
  const form = el('div', { className: 'venue-form-grid' })
  const countInput = document.createElement('input')
  countInput.className = 'venue-input'
  countInput.type = 'number'
  countInput.min = '1'
  countInput.max = '500'
  countInput.setAttribute('aria-label', 'Количество столов')
  countInput.placeholder = 'Количество столов'
  const startInput = document.createElement('input')
  startInput.className = 'venue-input'
  startInput.type = 'number'
  startInput.min = '1'
  startInput.setAttribute('aria-label', 'Стартовый номер')
  startInput.placeholder = 'Стартовый номер (опц)'
  const createButton = el('button', { text: 'Создать столы' }) as HTMLButtonElement
  const numbers = el('div', { className: 'venue-table-numbers' })
  const validation = el('p', { className: 'venue-table-validation' })
  validation.setAttribute('role', 'status')
  append(form, countInput, startInput, numbers, validation, createButton)

  const rotateAllButton = el('button', { className: 'button-secondary', text: 'Переиздать токены (все)' }) as HTMLButtonElement

  const exportRow = el('div', { className: 'venue-form-row' })
  const exportButton = el('button', { text: isTelegram ? 'Отправить все QR в Telegram' : 'Все QR в PDF' }) as HTMLButtonElement
  const zipButton = el('button', { className: 'button-secondary', text: 'ZIP для компьютера' }) as HTMLButtonElement
  const sendButton = el('button', { text: 'Отправить в Telegram' }) as HTMLButtonElement
  const downloadLink = el('a', { text: 'Открыть подготовленный файл' }) as HTMLAnchorElement
  downloadLink.hidden = true
  const hint = el('p', { text: isTelegram
    ? 'Все QR придут в PDF в ваш личный чат с ботом, QR отдельного стола — в PNG. Если чат недоступен, откройте бота и нажмите «Старт» или разблокируйте его, затем отправьте файл снова.'
    : 'Сохраните все QR в PDF или ZIP, QR отдельного стола — в PNG.' })
  append(exportRow, exportButton)
  if (!isTelegram) append(exportRow, sendButton, zipButton, downloadLink)
  append(exportRow, hint)

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
    numbers,
    validation,
    exportRow,
    exportButton,
    zipButton,
    sendButton,
    downloadLink,
    list
  }
}

function renderTableRow(
  table: VenueTableDto,
  canRotate: boolean,
  onRotate: (tableId: number) => void,
  canExport: boolean,
  isTelegram: boolean,
  onExport: (tableId: number, send: boolean) => void
) {
  const row = el('div', { className: 'venue-table-row' })
  const info = el('div', { className: 'venue-table-info' })
  append(
    info,
    el('strong', { text: table.tableLabel || `Стол №${table.tableNumber}` }),
    el('p', { className: 'venue-order-sub', text: `Token: ${table.activeTokenIssuedAt ? new Date(table.activeTokenIssuedAt).toLocaleString() : '—'}` })
  )

  const actions = el('div', { className: 'venue-table-actions' })
  if (canExport) {
    const png = el('button', { className: 'button-small', text: isTelegram ? 'Отправить QR в Telegram' : 'QR этого стола' })
    png.addEventListener('click', () => onExport(table.tableId, isTelegram))
    append(actions, png)
    if (!isTelegram) {
      const send = el('button', { className: 'button-small button-secondary', text: 'Отправить PNG в Telegram' })
      send.addEventListener('click', () => onExport(table.tableId, true))
      append(actions, send)
    }
  }
  const rotateButton = el('button', { className: 'button-small', text: 'Переиздать токен' }) as HTMLButtonElement
  rotateButton.addEventListener('click', () => onRotate(table.tableId))
  if (canRotate) {
    append(actions, rotateButton)
  }

  if (actions.childElementCount > 0) {
    append(row, info, actions)
  } else {
    append(row, info)
  }
  return row
}

export function renderVenueTablesScreen(options: VenueTablesOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const { hasTelegramLaunchData: isTelegram } = getTelegramContext()
  const refs = buildTablesDom(root, isTelegram)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let tables: VenueTableDto[] = []
  let tablesLoaded = false
  let creating = false
  let exporting = false

  const canView = access.permissions.includes('TABLE_VIEW')
  const canManage = access.permissions.includes('TABLE_MANAGE')
  const canRotate = access.permissions.includes('TABLE_TOKEN_ROTATE')
  const canRotateAll = access.permissions.includes('TABLE_TOKEN_ROTATE_ALL')
  const canExport = access.permissions.includes('TABLE_QR_EXPORT')
  if (!canManage) {
    refs.countInput.closest('.venue-form-grid')?.remove()
  }
  if (!canRotateAll) {
    refs.rotateAllButton.remove()
  }
  if (!canExport) {
    refs.exportRow.remove()
  }

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

  const showUnconfirmedDelivery = () => {
    refs.error.dataset.severity = 'warn'
    refs.errorTitle.textContent = 'Отправка не подтверждена'
    refs.errorMessage.textContent =
      'Файл мог быть отправлен. Проверьте личный чат с ботом перед повторной отправкой.'
    refs.errorActions.replaceChildren()
    refs.errorDetails.replaceChildren()
    refs.error.hidden = false
  }

  const renderTables = (tables: VenueTableDto[]) => {
    refs.list.replaceChildren()
    if (!tables.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Столы отсутствуют.' }))
      return
    }
    tables.forEach((table) => {
      refs.list.appendChild(renderTableRow(table, canRotate, (tableId) => void rotateToken(tableId), canExport, isTelegram, (tableId, send) => void exportQr('png', send, tableId)))
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
    tables = result.data.tables
    tablesLoaded = true
    renderTables(tables)
    updateNumbers()
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const positiveInteger = (raw: string) => /^\d+$/.test(raw.trim()) &&
    Number.isSafeInteger(Number(raw)) && Number(raw) > 0 && Number(raw) <= 2147483647

  const validateNumbers = () => {
    const inputs = Array.from(refs.numbers.querySelectorAll<HTMLInputElement>('input'))
    const count = Number(refs.countInput.value)
    let message = ''
    if (!tablesLoaded) message = 'Дождитесь загрузки занятых номеров.'
    else if (!positiveInteger(refs.countInput.value) || count > 500) message = 'Введите количество от 1 до 500.'
    else if (refs.startInput.value && !positiveInteger(refs.startInput.value)) message = 'Стартовый номер должен быть положительным целым числом.'
    else if (inputs.length !== count || inputs.some((input) => !positiveInteger(input.value))) message = 'Каждый номер должен быть целым числом от 1 до 2147483647.'
    const values = inputs.map((input) => Number(input.value))
    if (!message && new Set(values).size !== values.length) message = 'Номера повторяются. Укажите разные номера.'
    const occupied = values.filter((value) => tables.some((table) => table.tableNumber === value))
    if (!message && occupied.length) message = `Номера уже заняты: ${[...new Set(occupied)].join(', ')}. Исправьте их.`
    refs.validation.textContent = message || `Будет создано столов: ${count}. Номера: ${values.join(', ')}. У каждого будет собственный QR.`
    refs.createButton.disabled = !canManage || creating || Boolean(message)
    return message ? null : values
  }

  const updateNumbers = () => {
    const count = positiveInteger(refs.countInput.value) ? Number(refs.countInput.value) : 0
    if (count > 500 || count === 0) { validateNumbers(); return }
    const previous = Array.from(refs.numbers.querySelectorAll<HTMLInputElement>('input'))
    const nextNumber = Math.max(0, ...tables.map((table) => table.tableNumber)) + 1
    const start = refs.startInput.value ? Number(refs.startInput.value) : nextNumber
    const fields = Array.from({ length: count }, (_, index) => {
      const label = el('label', { text: `Стол ${index + 1}: номер` })
      const input = document.createElement('input')
      input.className = 'venue-input'
      input.type = 'text'
      input.inputMode = 'numeric'
      input.value = previous[index]?.dataset.edited === 'true' ? previous[index].value : String(start + index)
      input.dataset.edited = previous[index]?.dataset.edited ?? 'false'
      input.addEventListener('input', () => { input.dataset.edited = 'true'; validateNumbers() })
      label.appendChild(input)
      return label
    })
    refs.numbers.replaceChildren(...fields)
    validateNumbers()
  }

  const createTables = async () => {
    if (!canManage || creating) return
    const tableNumbers = validateNumbers()
    if (!tableNumbers) return
    creating = true
    refs.createButton.disabled = true
    const inputs = [refs.countInput, refs.startInput, ...Array.from(refs.numbers.querySelectorAll<HTMLInputElement>('input'))]
    inputs.forEach((input) => { input.disabled = true })
    hideError()
    try {
      const result = await venueCreateTablesBatch(backendUrl, { venueId, body: { count: tableNumbers.length, tableNumbers } }, deps)
      if (disposed) return
      if (!result.ok) { showError(result.error); return }
      showToast(`Создано столов: ${result.data.count}`)
      refs.countInput.value = ''
      refs.startInput.value = ''
      refs.numbers.replaceChildren()
      await loadTables()
    } finally {
      creating = false
      inputs.forEach((input) => { input.disabled = false })
      validateNumbers()
    }
  }

  const rotateToken = async (tableId: number) => {
    if (!canRotate) {
      showToast('Недостаточно прав')
      return
    }
    if (!window.confirm('Переиздать QR этого стола? Старый QR перестанет работать. Потребуется заменить распечатку.')) return
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
    if (!window.confirm('Переиздать QR всех столов? Все старые QR перестанут работать. Потребуется заменить распечатки.')) return
    const result = await venueRotateTableTokens(backendUrl, { venueId, body: {} }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast(`Переиздано токенов: ${result.data.rotatedCount}`)
    void loadTables()
  }

  const exportQr = async (format: 'pdf' | 'zip' | 'png', send = isTelegram, tableId?: number) => {
    if (!canExport || exporting) return
    if (isTelegram && format === 'zip') return
    const sendToTelegram = isTelegram || send
    exporting = true
    const buttons = [...Array.from(refs.exportRow.querySelectorAll<HTMLButtonElement>('button')),
      ...Array.from(refs.list.querySelectorAll<HTMLButtonElement>('button'))]
    buttons.forEach((button) => { button.disabled = true })
    hideError()
    setStatus(sendToTelegram ? 'Отправляем документ…' : 'Готовим файл…')
    try {
      if (sendToTelegram && format !== 'zip') {
        const result = await venueSendQrToTelegram(backendUrl, { venueId, format, tableId }, deps)
        if (disposed) return
        if (!result.ok) {
          const error = result.error
          if (error.status === 0 || error.status >= 500 || (typeof error.details === 'object' && error.details !== null &&
              'deliveryOutcome' in error.details && error.details.deliveryOutcome === 'unknown')) {
            showUnconfirmedDelivery()
          } else {
            showError(error)
          }
          return
        }
        if (result.data.sent === true) setStatus('Документ отправлен в ваш личный чат с ботом.')
        else showUnconfirmedDelivery()
        return
      }
      const result = await venueExportQrPackage(backendUrl, { venueId, format, tableId }, deps)
      if (disposed) return
      if (!result.ok) { showError(result.error); return }
      const url = URL.createObjectURL(result.data.blob)
      const link = refs.downloadLink
      link.href = url
      link.download = result.data.fileName ?? `venue-${venueId}-qr.${format}`
      link.hidden = false
      link.click()
      // Keep the URL alive for asynchronous browser/WebView reads and a manual retry.
      window.setTimeout(() => {
        URL.revokeObjectURL(url)
        if (link.getAttribute('href') === url) { link.removeAttribute('href'); link.hidden = true }
      }, 5 * 60 * 1000)
      setStatus('Файл подготовлен, сохранение запрошено. Если ничего не произошло, нажмите «Открыть подготовленный файл» или отправьте в Telegram.')
    } finally {
      exporting = false
      buttons.forEach((button) => { button.disabled = false })
      if (!disposed && !refs.error.hidden) setStatus('Действие не подтверждено. Форма сохранена.')
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.createButton, 'click', () => void createTables()))
  disposables.push(on(refs.rotateAllButton, 'click', () => void rotateAllTokens()))
  disposables.push(on(refs.exportButton, 'click', () => void exportQr('pdf')))
  disposables.push(on(refs.zipButton, 'click', () => void exportQr('zip')))
  disposables.push(on(refs.sendButton, 'click', () => void exportQr('pdf', true)))
  disposables.push(on(refs.countInput, 'input', updateNumbers))
  disposables.push(on(refs.startInput, 'input', updateNumbers))

  refs.createButton.disabled = !canManage
  refs.rotateAllButton.disabled = !canRotateAll
  refs.exportButton.disabled = !canExport

  void loadTables()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
