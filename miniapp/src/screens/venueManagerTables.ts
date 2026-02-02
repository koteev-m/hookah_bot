import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { freeTable, seatTable, updateDeposit } from '../shared/api/managerApi'
import type {
  DepositCategory,
  NightTableDepositDto,
  NightTableDto,
  NightTableAllocationDto
} from '../shared/api/managerDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { useNightTables } from '../shared/hooks/useNightTables'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueManagerTablesOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
}

type ManagerTablesRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  unauthorizedCard: HTMLDivElement
  nightKeyInput: HTMLInputElement
  reloadButton: HTMLButtonElement
  list: HTMLDivElement
  seatModal: HTMLDivElement
  seatModeQr: HTMLInputElement
  seatModeNoQr: HTMLInputElement
  seatQrInput: HTMLInputElement
  seatAmountInput: HTMLInputElement
  seatAllocationInputs: Record<DepositCategory, HTMLInputElement>
  seatAllocationSummary: HTMLParagraphElement
  seatSaveButton: HTMLButtonElement
  seatCancelButton: HTMLButtonElement
  editModal: HTMLDivElement
  editAmountInput: HTMLInputElement
  editAllocationInputs: Record<DepositCategory, HTMLInputElement>
  editAllocationSummary: HTMLParagraphElement
  editReasonInput: HTMLInputElement
  editSaveButton: HTMLButtonElement
  editCancelButton: HTMLButtonElement
}

type ModalHandle = {
  container: HTMLDivElement
  open: () => void
  close: () => void
}

const depositCategories: Array<{ key: DepositCategory; label: string }> = [
  { key: 'BAR', label: 'Бар' },
  { key: 'HOOKAH', label: 'Кальяны' },
  { key: 'VIP', label: 'VIP' },
  { key: 'OTHER', label: 'Другое' }
]

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

function buildModal(title: string): ModalHandle {
  const container = el('div', { className: 'manager-modal' })
  const card = el('div', { className: 'manager-modal-card card' })
  const heading = el('h3', { text: title })
  const content = el('div', { className: 'manager-modal-content' })
  const actions = el('div', { className: 'manager-modal-actions' })
  append(card, heading, content, actions)
  append(container, card)
  container.hidden = true

  const open = () => {
    container.hidden = false
    container.dataset.visible = 'true'
  }
  const close = () => {
    container.hidden = true
    container.dataset.visible = 'false'
  }

  container.addEventListener('click', (event) => {
    if (event.target === container) {
      close()
    }
  })

  return { container, open, close }
}

function buildAllocationsBlock(label: string) {
  const wrapper = el('div', { className: 'manager-deposit-block' })
  const title = el('p', { className: 'manager-deposit-title', text: label })
  const grid = el('div', { className: 'manager-deposit-grid' })
  const allocationInputs = {} as Record<DepositCategory, HTMLInputElement>
  depositCategories.forEach(({ key, label: categoryLabel }) => {
    const row = el('label', { className: 'manager-deposit-row' })
    const text = el('span', { text: categoryLabel })
    const input = document.createElement('input')
    input.type = 'number'
    input.min = '0'
    input.step = '0.01'
    input.className = 'venue-input'
    input.placeholder = '0'
    allocationInputs[key] = input
    append(row, text, input)
    grid.appendChild(row)
  })
  const summary = el('p', { className: 'manager-deposit-summary', text: 'Сумма распределения: 0' })
  append(wrapper, title, grid, summary)
  return { wrapper, allocationInputs, summary }
}

function parseNonNegative(value: string): number | null {
  const parsed = Number.parseFloat(value)
  if (!Number.isFinite(parsed) || parsed < 0) {
    return null
  }
  return parsed
}

type DepositPayloadResult =
  | { ok: true; payload: { amount: number; allocations: NightTableAllocationDto[] } }
  | { ok: false; message: string }

function readDepositPayload(
  amountInput: HTMLInputElement,
  allocationInputs: Record<DepositCategory, HTMLInputElement>
): DepositPayloadResult {
  const amount = parseNonNegative(amountInput.value.trim())
  if (amount === null) {
    return { ok: false, message: 'Введите сумму депозита (0 или больше).' }
  }
  const allocations: NightTableAllocationDto[] = []
  let allocationSum = 0
  for (const category of depositCategories) {
    const value = parseNonNegative(allocationInputs[category.key].value.trim())
    if (value === null) {
      return { ok: false, message: `Введите сумму для ${category.label}.` }
    }
    allocationSum += value
    allocations.push({ category: category.key, amount: value })
  }
  if (Math.abs(allocationSum - amount) > 0.001) {
    return { ok: false, message: 'Сумма распределения должна совпадать с депозитом.' }
  }
  return { ok: true as const, payload: { amount, allocations } }
}

function updateAllocationSummary(
  summary: HTMLParagraphElement,
  allocationInputs: Record<DepositCategory, HTMLInputElement>
) {
  let sum = 0
  for (const category of depositCategories) {
    const parsed = Number.parseFloat(allocationInputs[category.key].value.trim())
    if (Number.isFinite(parsed) && parsed >= 0) {
      sum += parsed
    }
  }
  summary.textContent = `Сумма распределения: ${sum}`
}

function resolveTableLabel(table: NightTableDto) {
  if (table.tableLabel) return table.tableLabel
  if (table.tableNumber !== undefined) return `Стол №${table.tableNumber}`
  return `Стол #${table.tableId}`
}

function resolveTableOccupied(table: NightTableDto) {
  if (typeof table.isOccupied === 'boolean') return table.isOccupied
  if (typeof table.isSeated === 'boolean') return table.isSeated
  if (typeof table.isActive === 'boolean') return table.isActive
  if (table.status) return table.status !== 'FREE'
  return false
}

function resolveDeposit(table: NightTableDto): NightTableDepositDto | null {
  if (table.deposit) return table.deposit
  return null
}

function resolveDepositId(table: NightTableDto): number | null {
  if (table.deposit?.depositId) return table.deposit.depositId
  if (table.deposit?.id) return table.deposit.id
  if (table.depositId) return table.depositId
  return null
}

function buildManagerTablesDom(root: HTMLDivElement): ManagerTablesRefs {
  const wrapper = el('div', { className: 'manager-tables' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Столы' })
  const subtitle = el('p', { className: 'venue-dashboard-subtitle', text: 'Управление посадкой и депозитами.' })
  const controls = el('div', { className: 'venue-form-grid' })
  const nightKeyInput = document.createElement('input')
  nightKeyInput.className = 'venue-input'
  nightKeyInput.placeholder = 'Ключ ночи (nightKey)'
  const reloadButton = el('button', { text: 'Показать столы' }) as HTMLButtonElement
  append(controls, nightKeyInput, reloadButton)
  append(header, title, subtitle, controls)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const unauthorizedCard = el('div', { className: 'error-card manager-unauthorized' })
  unauthorizedCard.hidden = true
  const unauthorizedTitle = el('h3', { text: 'Требуется авторизация' })
  const unauthorizedMessage = el('p', { text: 'Сессия истекла. Откройте Mini App заново в Telegram.' })
  const unauthorizedActions = el('div', { className: 'error-actions' })
  const reloadAuthButton = el('button', { text: 'Перезапустить' }) as HTMLButtonElement
  reloadAuthButton.addEventListener('click', () => window.location.reload())
  append(unauthorizedActions, reloadAuthButton)
  append(unauthorizedCard, unauthorizedTitle, unauthorizedMessage, unauthorizedActions)

  const list = el('div', { className: 'venue-table-list' })

  const seatModal = buildModal('Посадка за стол')
  const seatContent = seatModal.container.querySelector('.manager-modal-content') as HTMLDivElement
  const seatActions = seatModal.container.querySelector('.manager-modal-actions') as HTMLDivElement
  const seatModes = el('div', { className: 'manager-seat-modes' })
  const seatModeQr = document.createElement('input')
  seatModeQr.type = 'radio'
  seatModeQr.name = 'seatMode'
  seatModeQr.value = 'WITH_QR'
  seatModeQr.checked = true
  const seatModeQrLabel = el('label', { className: 'manager-seat-mode' })
  const seatModeQrText = el('span', { text: 'С QR' })
  append(seatModeQrLabel, seatModeQr, seatModeQrText)
  const seatModeNoQr = document.createElement('input')
  seatModeNoQr.type = 'radio'
  seatModeNoQr.name = 'seatMode'
  seatModeNoQr.value = 'NO_QR'
  const seatModeNoQrLabel = el('label', { className: 'manager-seat-mode' })
  const seatModeNoQrText = el('span', { text: 'Без QR' })
  append(seatModeNoQrLabel, seatModeNoQr, seatModeNoQrText)
  append(seatModes, seatModeQrLabel, seatModeNoQrLabel)

  const seatQrInput = document.createElement('input')
  seatQrInput.className = 'venue-input'
  seatQrInput.placeholder = 'QR строка'

  const seatAmountInput = document.createElement('input')
  seatAmountInput.className = 'venue-input'
  seatAmountInput.type = 'number'
  seatAmountInput.min = '0'
  seatAmountInput.step = '0.01'
  seatAmountInput.placeholder = 'Сумма депозита'

  const seatAllocations = buildAllocationsBlock('Распределение депозита')

  append(seatContent, seatModes, seatQrInput, seatAmountInput, seatAllocations.wrapper)
  const seatCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const seatSaveButton = el('button', { text: 'Сохранить' }) as HTMLButtonElement
  append(seatActions, seatCancelButton, seatSaveButton)

  const editModal = buildModal('Редактировать депозит')
  const editContent = editModal.container.querySelector('.manager-modal-content') as HTMLDivElement
  const editActions = editModal.container.querySelector('.manager-modal-actions') as HTMLDivElement
  const editAmountInput = document.createElement('input')
  editAmountInput.className = 'venue-input'
  editAmountInput.type = 'number'
  editAmountInput.min = '0'
  editAmountInput.step = '0.01'
  editAmountInput.placeholder = 'Сумма депозита'
  const editAllocations = buildAllocationsBlock('Распределение депозита')
  const editReasonInput = document.createElement('input')
  editReasonInput.className = 'venue-input'
  editReasonInput.placeholder = 'Причина изменения (обязательно)'
  append(editContent, editAmountInput, editAllocations.wrapper, editReasonInput)
  const editCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const editSaveButton = el('button', { text: 'Сохранить изменения' }) as HTMLButtonElement
  append(editActions, editCancelButton, editSaveButton)

  append(wrapper, header, status, error, unauthorizedCard, list, seatModal.container, editModal.container)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    unauthorizedCard,
    nightKeyInput,
    reloadButton,
    list,
    seatModal: seatModal.container,
    seatModeQr,
    seatModeNoQr,
    seatQrInput,
    seatAmountInput,
    seatAllocationInputs: seatAllocations.allocationInputs,
    seatAllocationSummary: seatAllocations.summary,
    seatSaveButton,
    seatCancelButton,
    editModal: editModal.container,
    editAmountInput,
    editAllocationInputs: editAllocations.allocationInputs,
    editAllocationSummary: editAllocations.summary,
    editReasonInput,
    editSaveButton,
    editCancelButton
  }
}

export function renderVenueManagerTablesScreen(options: VenueManagerTablesOptions) {
  const { root, backendUrl, isDebug, venueId } = options
  if (!root) return () => undefined
  const refs = buildManagerTablesDom(root)
  const deps = buildApiDeps(isDebug)
  const tablesStore = useNightTables(backendUrl, deps, venueId)

  let disposed = false
  let activeTable: NightTableDto | null = null

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const hideError = () => {
    refs.error.hidden = true
    refs.unauthorizedCard.hidden = true
  }

  const renderUnauthorized = () => {
    refs.unauthorizedCard.hidden = false
    refs.error.hidden = true
  }

  const renderError = (error: ApiErrorInfo, actions?: ApiErrorAction[]) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }
    const presentation = presentApiError(error, { isDebug })
    refs.error.dataset.severity = presentation.severity
    refs.errorTitle.textContent = presentation.title
    refs.errorMessage.textContent = presentation.message
    renderErrorActions(refs.errorActions, actions ?? presentation.actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
    refs.unauthorizedCard.hidden = true
  }

  const renderTables = (tables: NightTableDto[]) => {
    refs.list.replaceChildren()
    if (!tables.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Столы не найдены.' }))
      return
    }
    tables.forEach((table) => {
      const occupied = resolveTableOccupied(table)
      const deposit = resolveDeposit(table)
      const row = el('div', { className: 'venue-table-row' })
      const info = el('div', { className: 'venue-table-info' })
      const statusText = occupied ? 'Занят' : 'Свободен'
      const depositText = deposit ? `Депозит: ${deposit.amount}` : 'Депозит: —'
      append(
        info,
        el('strong', { text: resolveTableLabel(table) }),
        el('p', { className: 'venue-order-sub', text: statusText }),
        el('p', { className: 'venue-order-sub', text: depositText })
      )
      const actions = el('div', { className: 'venue-table-actions' })
      const seatButton = el('button', { className: 'button-small', text: 'Посадить' }) as HTMLButtonElement
      seatButton.disabled = occupied
      seatButton.addEventListener('click', () => openSeatModal(table))
      const freeButton = el('button', { className: 'button-small button-secondary', text: 'Освободить' }) as HTMLButtonElement
      freeButton.disabled = !occupied
      freeButton.addEventListener('click', () => void handleFree(table))
      const editButton = el('button', { className: 'button-small', text: 'Редактировать депозит' }) as HTMLButtonElement
      editButton.disabled = !deposit
      editButton.addEventListener('click', () => openEditModal(table))
      append(actions, seatButton, freeButton, editButton)
      append(row, info, actions)
      refs.list.appendChild(row)
    })
  }

  const openSeatModal = (table: NightTableDto) => {
    activeTable = table
    refs.seatModeQr.checked = true
    refs.seatModeNoQr.checked = false
    refs.seatQrInput.value = ''
    refs.seatQrInput.disabled = false
    refs.seatAmountInput.value = '0'
    depositCategories.forEach(({ key }) => {
      refs.seatAllocationInputs[key].value = '0'
    })
    updateAllocationSummary(refs.seatAllocationSummary, refs.seatAllocationInputs)
    refs.seatModal.hidden = false
  }

  const openEditModal = (table: NightTableDto) => {
    const deposit = resolveDeposit(table)
    if (!deposit) {
      showToast('Нет депозита для редактирования.')
      return
    }
    activeTable = table
    refs.editAmountInput.value = String(deposit.amount ?? 0)
    depositCategories.forEach(({ key }) => {
      const found = deposit.allocations?.find((item) => item.category === key)
      refs.editAllocationInputs[key].value = String(found?.amount ?? 0)
    })
    updateAllocationSummary(refs.editAllocationSummary, refs.editAllocationInputs)
    refs.editReasonInput.value = ''
    refs.editModal.hidden = false
  }

  const closeSeatModal = () => {
    refs.seatModal.hidden = true
  }

  const closeEditModal = () => {
    refs.editModal.hidden = true
  }

  const handleSeat = async () => {
    if (!activeTable) return
    const nightKey = refs.nightKeyInput.value.trim()
    if (!nightKey) {
      showToast('Укажите ночь.')
      return
    }
    const mode = refs.seatModeQr.checked ? 'WITH_QR' : 'NO_QR'
    const qr = refs.seatQrInput.value.trim()
    if (mode === 'WITH_QR' && !qr) {
      showToast('Введите QR строку.')
      return
    }
    const depositResult = readDepositPayload(refs.seatAmountInput, refs.seatAllocationInputs)
    if (!depositResult.ok) {
      showToast(depositResult.message)
      return
    }
    refs.seatSaveButton.disabled = true
    const result = await seatTable(
      backendUrl,
      venueId,
      nightKey,
      activeTable.tableId,
      {
        mode,
        qr: mode === 'WITH_QR' ? qr : undefined,
        deposit: depositResult.payload
      },
      deps
    )
    refs.seatSaveButton.disabled = false
    if (disposed) return
    if (!result.ok) {
      renderError(result.error)
      return
    }
    showToast('Гость посажен.')
    closeSeatModal()
    await reloadTables()
  }

  const handleFree = async (table: NightTableDto) => {
    const nightKey = refs.nightKeyInput.value.trim()
    if (!nightKey) {
      showToast('Укажите ночь.')
      return
    }
    const confirmation = window.confirm(`Освободить ${resolveTableLabel(table)}?`)
    if (!confirmation) {
      return
    }
    const result = await freeTable(backendUrl, venueId, nightKey, table.tableId, deps)
    if (disposed) return
    if (!result.ok) {
      renderError(result.error)
      return
    }
    showToast('Стол освобождён.')
    await reloadTables()
  }

  const handleDepositUpdate = async () => {
    if (!activeTable) return
    const nightKey = refs.nightKeyInput.value.trim()
    if (!nightKey) {
      showToast('Укажите ночь.')
      return
    }
    const depositId = resolveDepositId(activeTable)
    if (!depositId) {
      showToast('Не удалось определить депозит.')
      return
    }
    const reason = refs.editReasonInput.value.trim()
    if (!reason) {
      showToast('Укажите причину изменения.')
      return
    }
    const depositResult = readDepositPayload(refs.editAmountInput, refs.editAllocationInputs)
    if (!depositResult.ok) {
      showToast(depositResult.message)
      return
    }
    refs.editSaveButton.disabled = true
    const result = await updateDeposit(
      backendUrl,
      venueId,
      nightKey,
      depositId,
      {
        ...depositResult.payload,
        reason
      },
      deps
    )
    refs.editSaveButton.disabled = false
    if (disposed) return
    if (!result.ok) {
      renderError(result.error)
      return
    }
    showToast('Депозит обновлён.')
    closeEditModal()
    await reloadTables()
  }

  const reloadTables = async () => {
    const nightKey = refs.nightKeyInput.value.trim()
    tablesStore.setParams(venueId, nightKey || undefined)
    if (!nightKey) {
      tablesStore.abort()
      setStatus('Укажите ночь для загрузки столов.')
      refs.list.replaceChildren(el('p', { className: 'venue-empty', text: 'Выберите ночь.' }))
      hideError()
      return
    }
    hideError()
    await tablesStore.reload()
  }

  const syncSeatQrVisibility = () => {
    const isQr = refs.seatModeQr.checked
    refs.seatQrInput.disabled = !isQr
    refs.seatQrInput.classList.toggle('is-hidden', !isQr)
  }

  const updateState = () => {
    const state = tablesStore.getState()
    if (state.status === 'loading') {
      setStatus('Загрузка...')
      return
    }
    if (state.status === 'unauthorized') {
      setStatus('')
      renderUnauthorized()
      refs.list.replaceChildren()
      return
    }
    if (state.status === 'error') {
      setStatus('')
      if (state.error) {
        const presentation = presentApiError(state.error, { isDebug })
        const actions = presentation.actions.length
          ? presentation.actions.map((action) => {
              if (action.label === 'Повторить') {
                return { ...action, onClick: () => void reloadTables() }
              }
              return action
            })
          : state.canRetry
            ? [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void reloadTables() }]
            : []
        renderError({ ...state.error, message: presentation.message }, actions)
      }
      return
    }
    if (state.status === 'ready' && state.data) {
      renderTables(state.data.tables)
      setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
      return
    }
    if (state.status === 'idle') {
      setStatus('Укажите ночь для загрузки.')
    }
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.reloadButton, 'click', () => void reloadTables()))
  disposables.push(on(refs.seatCancelButton, 'click', closeSeatModal))
  disposables.push(on(refs.editCancelButton, 'click', closeEditModal))
  disposables.push(on(refs.seatSaveButton, 'click', () => void handleSeat()))
  disposables.push(on(refs.editSaveButton, 'click', () => void handleDepositUpdate()))
  disposables.push(on(refs.seatModeQr, 'change', syncSeatQrVisibility))
  disposables.push(on(refs.seatModeNoQr, 'change', syncSeatQrVisibility))

  Object.values(refs.seatAllocationInputs).forEach((input) => {
    disposables.push(on(input, 'input', () => updateAllocationSummary(refs.seatAllocationSummary, refs.seatAllocationInputs)))
  })
  Object.values(refs.editAllocationInputs).forEach((input) => {
    disposables.push(on(input, 'input', () => updateAllocationSummary(refs.editAllocationSummary, refs.editAllocationInputs)))
  })

  const nightKeyFromUrl = new URLSearchParams(window.location.search).get('nightKey')
  if (nightKeyFromUrl) {
    refs.nightKeyInput.value = nightKeyFromUrl
  }

  const unsubscribe = tablesStore.subscribe(() => updateState())
  void reloadTables()
  syncSeatQrVisibility()

  return () => {
    disposed = true
    tablesStore.abort()
    unsubscribe()
    disposables.forEach((dispose) => dispose())
  }
}
