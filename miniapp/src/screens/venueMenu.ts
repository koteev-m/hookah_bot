import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueApplyBaseFlavorProfiles,
  venueCreateCategory,
  venueCreateItem,
  venueCreateOption,
  venueDeleteCategory,
  venueDeleteItem,
  venueDeleteOption,
  venueCompleteMenuShiftCheck,
  venueGetMenu,
  venueReorderCategories,
  venueReorderItems,
  venueSetItemAvailability,
  venueSetOptionAvailability,
  venueUpdateCategory,
  venueUpdateItem,
  venueUpdateOption
} from '../shared/api/venueApi'
import type { VenueAccessDto, VenueMenuCategoryDto, VenueMenuItemDto, VenueMenuOptionDto } from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const DEFAULT_CURRENCY = 'RUB'

export type VenueMenuOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
}

type MenuRefs = {
  status: HTMLParagraphElement
  success: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  editingDetails: HTMLDetailsElement
  editingCategoryCount: HTMLSpanElement
  editingItemCount: HTMLSpanElement
  categories: HTMLDivElement
  createCategoryAction: HTMLButtonElement
  createCategoryForm: HTMLDivElement
  createCategoryInput: HTMLInputElement
  createCategoryButton: HTMLButtonElement
  shiftCheck: ShiftCheckRefs | null
}

type ShiftCheckFilter = 'all' | 'unavailable' | 'dirty'

type ShiftCheckRefs = {
  details: HTMLDetailsElement
  availableItemCount: HTMLSpanElement
  totalItemCount: HTMLSpanElement
  availableOptionCount: HTMLSpanElement
  totalOptionCount: HTMLSpanElement
  dirtyCount: HTMLSpanElement
  dirtySummary: HTMLSpanElement
  searchInput: HTMLInputElement
  filterButtons: Record<ShiftCheckFilter, HTMLButtonElement>
  massModeButton: HTMLButtonElement
  bulkToolbar: HTMLDivElement
  selectedCount: HTMLElement
  selectFilteredButton: HTMLButtonElement
  selectedAvailableButton: HTMLButtonElement
  selectedUnavailableButton: HTMLButtonElement
  clearSelectionButton: HTMLButtonElement
  exitMassModeButton: HTMLButtonElement
  categories: HTMLDivElement
  itemMadeAvailable: HTMLSpanElement
  itemMadeUnavailable: HTMLSpanElement
  optionMadeAvailable: HTMLSpanElement
  optionMadeUnavailable: HTMLSpanElement
  confirmButton: HTMLButtonElement
  cancelButton: HTMLButtonElement
  status: HTMLParagraphElement
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

function buildShiftCheckDom(): ShiftCheckRefs {
  const details = el('details', { className: 'card venue-menu-shift-check' })
  const summary = el('summary', { className: 'venue-menu-section-summary' })
  const summaryTitle = el('span', {
    className: 'venue-menu-section-title',
    text: 'Проверка меню перед сменой'
  })
  const summaryDescription = el('span', {
    className: 'venue-menu-section-description',
    text: 'Проверьте наличие позиций и вариантов. Изменения применятся только после подтверждения.'
  })
  const summaryMetrics = el('span', { className: 'venue-menu-section-metrics' })
  const availableItemCount = el('span', { text: '0' })
  const totalItemCount = el('span', { text: '0' })
  const availableOptionCount = el('span', { text: '0' })
  const totalOptionCount = el('span', { text: '0' })
  const dirtyCount = el('span', { text: '0' })
  const itemMetric = el('span', { text: 'Позиции: ' })
  append(itemMetric, availableItemCount, document.createTextNode('/'), totalItemCount)
  const optionMetric = el('span', { text: 'Опции: ' })
  append(optionMetric, availableOptionCount, document.createTextNode('/'), totalOptionCount)
  const dirtySummary = el('span', {
    className: 'venue-menu-section-dirty-summary',
    text: 'Несохранённые изменения: '
  })
  dirtySummary.hidden = true
  dirtySummary.appendChild(dirtyCount)
  append(summaryMetrics, itemMetric, optionMetric, dirtySummary)
  append(summary, summaryTitle, summaryDescription, summaryMetrics)

  const controls = el('div', { className: 'venue-shift-check-controls' })
  const searchInput = document.createElement('input')
  searchInput.className = 'venue-input'
  searchInput.type = 'search'
  searchInput.placeholder = 'Поиск по позициям и опциям'
  searchInput.setAttribute('aria-label', 'Поиск по позициям и опциям')

  const filters = el('div', { className: 'venue-shift-check-filters' })
  const filterButtons = {
    all: el('button', { className: 'button-small button-secondary', text: 'Все' }) as HTMLButtonElement,
    unavailable: el('button', {
      className: 'button-small button-secondary',
      text: 'Нет в наличии'
    }) as HTMLButtonElement,
    dirty: el('button', {
      className: 'button-small button-secondary',
      text: 'Есть несохранённые изменения'
    }) as HTMLButtonElement
  }
  append(filters, filterButtons.all, filterButtons.unavailable, filterButtons.dirty)

  const massModeButton = el('button', {
    className: 'button-small button-secondary venue-shift-check-mass-mode',
    text: 'Массовое изменение'
  }) as HTMLButtonElement
  massModeButton.type = 'button'

  const bulkToolbar = el('div', {
    className: 'venue-shift-check-selection-actions venue-shift-check-bulk-toolbar'
  })
  bulkToolbar.hidden = true
  const selectedCount = el('strong', { text: 'Выбрано: 0' })
  selectedCount.setAttribute('role', 'status')
  selectedCount.setAttribute('aria-live', 'polite')
  const selectFilteredButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Выбрать все отфильтрованные'
  }) as HTMLButtonElement
  const selectedAvailableButton = el('button', {
    className: 'button-small',
    text: 'Сделать доступными'
  }) as HTMLButtonElement
  const selectedUnavailableButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Сделать недоступными'
  }) as HTMLButtonElement
  const clearSelectionButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Снять выбор'
  }) as HTMLButtonElement
  const exitMassModeButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Выйти из массового режима'
  }) as HTMLButtonElement
  append(
    bulkToolbar,
    selectedCount,
    selectFilteredButton,
    selectedAvailableButton,
    selectedUnavailableButton,
    clearSelectionButton,
    exitMassModeButton
  )
  append(controls, searchInput, filters, massModeButton, bulkToolbar)

  const categories = el('div', { className: 'venue-shift-check-categories' })

  const confirmation = el('section', { className: 'venue-shift-check-confirmation' })
  const confirmationTitle = el('h4', { text: 'Будет изменено:' })
  const confirmationGrid = el('div', { className: 'venue-shift-check-confirmation-grid' })
  const itemSummary = el('div')
  const itemMadeAvailable = el('span', { text: '0' })
  const itemMadeUnavailable = el('span', { text: '0' })
  append(
    itemSummary,
    el('strong', { text: 'Позиции:' }),
    el('p', { text: 'Доступны: ' }),
    el('p', { text: 'Недоступны: ' })
  )
  itemSummary.children[1].appendChild(itemMadeAvailable)
  itemSummary.children[2].appendChild(itemMadeUnavailable)

  const optionSummary = el('div')
  const optionMadeAvailable = el('span', { text: '0' })
  const optionMadeUnavailable = el('span', { text: '0' })
  append(
    optionSummary,
    el('strong', { text: 'Опции:' }),
    el('p', { text: 'Доступны: ' }),
    el('p', { text: 'Недоступны: ' })
  )
  optionSummary.children[1].appendChild(optionMadeAvailable)
  optionSummary.children[2].appendChild(optionMadeUnavailable)
  append(confirmationGrid, itemSummary, optionSummary)

  const confirmationActions = el('div', { className: 'venue-shift-check-confirmation-actions' })
  const confirmButton = el('button', { text: 'Подтвердить проверку' }) as HTMLButtonElement
  const cancelButton = el('button', {
    className: 'button-secondary',
    text: 'Отменить изменения'
  }) as HTMLButtonElement
  append(confirmationActions, confirmButton, cancelButton)
  const status = el('p', { className: 'status', text: '' })
  append(confirmation, confirmationTitle, confirmationGrid, confirmationActions, status)

  append(details, summary, controls, categories, confirmation)

  return {
    details,
    availableItemCount,
    totalItemCount,
    availableOptionCount,
    totalOptionCount,
    dirtyCount,
    dirtySummary,
    searchInput,
    filterButtons,
    massModeButton,
    bulkToolbar,
    selectedCount,
    selectFilteredButton,
    selectedAvailableButton,
    selectedUnavailableButton,
    clearSelectionButton,
    exitMassModeButton,
    categories,
    itemMadeAvailable,
    itemMadeUnavailable,
    optionMadeAvailable,
    optionMadeUnavailable,
    confirmButton,
    cancelButton,
    status
  }
}

function buildMenuDom(root: HTMLDivElement, canShiftCheck: boolean): MenuRefs {
  const wrapper = el('div', { className: 'venue-menu-builder' })
  const title = el('h2', { text: 'Меню' })

  const editingDetails = el('details', { className: 'card venue-menu-editing' })
  const editingSummary = el('summary', { className: 'venue-menu-section-summary' })
  const editingTitle = el('span', {
    className: 'venue-menu-section-title',
    text: 'Редактирование меню'
  })
  const editingDescription = el('span', {
    className: 'venue-menu-section-description',
    text: 'Категории, позиции, цены, опции и топ-лист.'
  })
  const editingMetrics = el('span', { className: 'venue-menu-section-metrics' })
  const editingCategoryCount = el('span', { text: '0' })
  const editingItemCount = el('span', { text: '0' })
  const categoryMetric = el('span', { text: 'Категории: ' })
  append(categoryMetric, editingCategoryCount)
  const itemMetric = el('span', { text: 'Позиции: ' })
  append(itemMetric, editingItemCount)
  append(editingMetrics, categoryMetric, itemMetric)
  append(editingSummary, editingTitle, editingDescription, editingMetrics)

  const editingBody = el('div', { className: 'venue-menu-editing-body' })
  const createCategoryAction = el('button', {
    className: 'button-secondary',
    text: 'Добавить категорию'
  }) as HTMLButtonElement
  createCategoryAction.type = 'button'
  createCategoryAction.setAttribute('aria-expanded', 'false')
  const createCategoryForm = el('div', { className: 'venue-form-row venue-menu-create-category' })
  createCategoryForm.id = 'venue-menu-create-category-form'
  createCategoryForm.hidden = true
  createCategoryAction.setAttribute('aria-controls', createCategoryForm.id)
  const createCategoryInput = document.createElement('input')
  createCategoryInput.className = 'venue-input'
  createCategoryInput.placeholder = 'Новая категория'
  createCategoryInput.setAttribute('aria-label', 'Название новой категории')
  const createCategoryButton = el('button', { text: 'Добавить' }) as HTMLButtonElement
  append(createCategoryForm, createCategoryInput, createCategoryButton)

  const categories = el('div', { className: 'venue-menu-categories' })
  append(editingBody, createCategoryAction, createCategoryForm, categories)
  append(editingDetails, editingSummary, editingBody)

  const status = el('p', { className: 'status', text: '' })
  const success = el('p', { className: 'venue-menu-success', text: '' })
  success.setAttribute('role', 'status')
  success.setAttribute('aria-live', 'polite')
  success.setAttribute('aria-atomic', 'true')
  success.hidden = true

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const shiftCheck = canShiftCheck ? buildShiftCheckDom() : null

  append(wrapper, title, status, success, error, editingDetails, shiftCheck?.details)
  root.replaceChildren(wrapper)

  return {
    status,
    success,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    editingDetails,
    editingCategoryCount,
    editingItemCount,
    categories,
    createCategoryAction,
    createCategoryForm,
    createCategoryInput,
    createCategoryButton,
    shiftCheck
  }
}

function parsePriceMinor(raw: string): number | null {
  const normalized = raw.replace(',', '.').trim()
  if (!normalized) return null
  const value = Number.parseFloat(normalized)
  if (!Number.isFinite(value) || value <= 0) {
    return null
  }
  return Math.round(value * 100)
}

function parseOptionPriceDeltaMinor(raw: string): number | null {
  const normalized = raw.replace(',', '.').trim()
  if (!normalized) return 0
  const value = Number.parseFloat(normalized)
  if (!Number.isFinite(value) || value < 0) {
    return null
  }
  return Math.round(value * 100)
}

function formatOptionPrice(option: VenueMenuOptionDto, currency: string) {
  if (option.priceDeltaMinor <= 0) {
    return ''
  }
  return `+${formatPrice(option.priceDeltaMinor, currency)}`
}

function isHookahMenuItem(item: VenueMenuItemDto) {
  return item.effectiveItemType === 'HOOKAH' || item.supportsBaseFlavorProfiles === true
}

function getScopedOptions(item: VenueMenuItemDto) {
  return (item.options ?? []).filter((option) => option.itemId === item.id)
}

function getOptionCopy(isHookah: boolean) {
  return {
    title: isHookah ? 'Вкусы / опции' : 'Опции',
    addButton: isHookah ? 'Добавить вкус' : 'Добавить опцию',
    editButton: isHookah ? 'Править вкус' : 'Править опцию',
    deleteButton: isHookah ? 'Удалить вкус' : 'Удалить опцию',
    nameLabel: isHookah ? 'Название вкуса' : 'Название опции',
    priceLabel: isHookah ? 'Доплата к вкусу, ₽' : 'Доплата к опции, ₽',
    deleteConfirm: isHookah ? 'Удалить вкус?' : 'Удалить опцию?',
    emptyText: 'Добавьте вкусы, чтобы гости выбирали их при заказе.',
    addedToast: isHookah ? 'Вкус добавлен' : 'Опция добавлена',
    updatedToast: isHookah ? 'Вкус обновлён' : 'Опция обновлена',
    deletedToast: isHookah ? 'Вкус удалён' : 'Опция удалена',
    enabledToast: isHookah ? 'Вкус доступен гостям' : 'Опция доступна гостям',
    disabledToast: isHookah ? 'Вкус скрыт от гостей' : 'Опция скрыта от гостей'
  }
}

type MutationFailureHandler = (message: string) => void

type MenuMutationHandlers = {
  onEditItem: (
    item: VenueMenuItemDto,
    name: string,
    priceMinor: number,
    onFailure?: MutationFailureHandler
  ) => void
  onDeleteItem: (item: VenueMenuItemDto, onFailure?: MutationFailureHandler) => void
  onSetItemAvailability: (
    item: VenueMenuItemDto,
    isAvailable: boolean,
    onFailure?: MutationFailureHandler
  ) => void
  onMoveItem: (item: VenueMenuItemDto, direction: 'up' | 'down', onFailure?: MutationFailureHandler) => void
  onCreateOption: (
    item: VenueMenuItemDto,
    name: string,
    priceDeltaMinor: number,
    onFailure?: MutationFailureHandler
  ) => void
  onApplyBaseFlavorProfiles: (item: VenueMenuItemDto, onFailure?: MutationFailureHandler) => void
  onEditOption: (
    option: VenueMenuOptionDto,
    name: string,
    priceDeltaMinor: number,
    onFailure?: MutationFailureHandler
  ) => void
  onDeleteOption: (option: VenueMenuOptionDto, onFailure?: MutationFailureHandler) => void
  onSetOptionAvailability: (
    option: VenueMenuOptionDto,
    isAvailable: boolean,
    onFailure?: MutationFailureHandler
  ) => void
}

function buildField(labelText: string, placeholder: string, value = '') {
  const field = el('label', { className: 'venue-menu-field' })
  const label = el('span', { className: 'venue-menu-field-label', text: labelText })
  const input = document.createElement('input')
  input.className = 'venue-input'
  input.placeholder = placeholder
  input.value = value
  append(field, label, input)
  return { field, input }
}

function buildPriceField(labelText: string, placeholder: string, value = '') {
  const { field, input } = buildField(labelText, placeholder, value)
  input.inputMode = 'decimal'
  input.autocomplete = 'off'
  input.setAttribute('data-menu-price-input', 'true')
  if (value === '0') {
    input.addEventListener('focus', () => {
      if (input.value === '0') input.select()
    })
  }
  return { field, input }
}

function buildMutationFeedback() {
  const feedback = el('p', { className: 'venue-menu-mutation-feedback', text: '' })
  feedback.setAttribute('role', 'status')
  feedback.setAttribute('aria-live', 'polite')
  feedback.hidden = true
  return feedback
}

function showMutationFeedback(target: HTMLElement, message: string) {
  target.textContent = message
  target.hidden = false
}

function focusConnectedMenuTarget(...targets: Array<HTMLElement | null>) {
  const target = targets.find((candidate) => candidate?.isConnected)
  target?.focus({ preventScroll: true })
}

function focusMenuControl(scope: HTMLElement, control: string, fallbackSelector: string) {
  focusConnectedMenuTarget(
    scope.querySelector<HTMLElement>(`[data-menu-control="${control}"]`),
    scope.querySelector<HTMLElement>(fallbackSelector)
  )
}

function renderOptionRow(
  option: VenueMenuOptionDto,
  itemName: string,
  currency: string,
  canManage: boolean,
  canManageAvailability: boolean,
  copy: ReturnType<typeof getOptionCopy>,
  handlers: Pick<MenuMutationHandlers, 'onEditOption' | 'onDeleteOption' | 'onSetOptionAvailability'>
) {
  const row = el('div', { className: 'venue-menu-option' })
  row.dataset.optionId = String(option.id)
  const feedback = buildMutationFeedback()

  const renderReadOnly = () => {
    const info = el('div', { className: 'venue-menu-option-info' })
    const name = el('span', { text: option.name })
    name.dataset.menuFocus = 'true'
    name.tabIndex = -1
    append(info, name)
    const price = formatOptionPrice(option, currency)
    if (price) info.appendChild(el('span', { className: 'venue-menu-item-price', text: price }))
    if (!option.isAvailable) info.appendChild(el('span', { className: 'menu-item-badge', text: 'Стоп-лист' }))

    const actions = el('div', { className: 'venue-menu-option-actions' })
    if (canManageAvailability) {
      const availabilityLabel = el('label', { className: 'venue-menu-option-toggle' })
      const availabilityInput = document.createElement('input')
      const availabilityText = el('span', {
        text: option.isAvailable ? 'Доступен гостям' : 'В стоп-листе'
      })
      availabilityInput.type = 'checkbox'
      availabilityInput.checked = option.isAvailable
      availabilityInput.dataset.menuControl = 'option-availability'
      availabilityInput.setAttribute(
        'aria-label',
        option.isAvailable
          ? `Доступен гостям: вариант ${option.name} для ${itemName}`
          : `В стоп-листе: вариант ${option.name} для ${itemName}`
      )
      availabilityInput.addEventListener('change', () => {
        availabilityInput.disabled = true
        handlers.onSetOptionAvailability(option, availabilityInput.checked, (message) => {
          availabilityInput.checked = option.isAvailable
          availabilityInput.disabled = false
          showMutationFeedback(feedback, message)
        })
      })
      append(availabilityLabel, availabilityInput, availabilityText)
      actions.appendChild(availabilityLabel)
    }

    if (canManage) {
      const editButton = el('button', { className: 'button-small', text: copy.editButton }) as HTMLButtonElement
      const deleteButton = el('button', {
        className: 'button-small button-danger',
        text: copy.deleteButton
      }) as HTMLButtonElement
      editButton.dataset.menuControl = 'option-edit'
      deleteButton.dataset.menuControl = 'option-delete'
      editButton.addEventListener('click', renderEditForm)
      deleteButton.addEventListener('click', () => {
        if (!window.confirm(copy.deleteConfirm)) return
        handlers.onDeleteOption(option, (message) => showMutationFeedback(feedback, message))
      })
      append(actions, editButton, deleteButton)
    }
    row.replaceChildren(info, actions, feedback)
  }

  const renderEditForm = () => {
    const form = el('form', { className: 'venue-menu-inline-form venue-menu-option-edit-form' })
    const name = buildField(copy.nameLabel, copy.nameLabel, option.name)
    const price = buildPriceField(copy.priceLabel, 'Например 150', String(option.priceDeltaMinor / 100))
    const actions = el('div', { className: 'venue-inline-actions' })
    const save = el('button', { className: 'button-small', text: 'Сохранить' }) as HTMLButtonElement
    const cancel = el('button', {
      className: 'button-small button-secondary',
      text: 'Отменить'
    }) as HTMLButtonElement
    save.type = 'submit'
    cancel.type = 'button'
    cancel.addEventListener('click', () => {
      renderReadOnly()
      focusMenuControl(row, 'option-edit', '[data-menu-focus="true"]')
    })
    form.addEventListener('submit', (event) => {
      event.preventDefault()
      const priceDeltaMinor = parseOptionPriceDeltaMinor(price.input.value)
      const trimmed = name.input.value.trim()
      if (!trimmed || priceDeltaMinor === null) {
        showMutationFeedback(feedback, 'Проверьте название и доплату.')
        return
      }
      feedback.hidden = true
      handlers.onEditOption(option, trimmed, priceDeltaMinor, (message) => showMutationFeedback(feedback, message))
    })
    append(form, name.field, price.field, actions)
    append(actions, save, cancel)
    row.replaceChildren(form, feedback)
    name.input.focus()
  }

  renderReadOnly()
  return row
}

function renderItemRow(
  item: VenueMenuItemDto,
  canManage: boolean,
  canManageAvailability: boolean,
  handlers: MenuMutationHandlers
) {
  const itemOptions = getScopedOptions(item)
  const isHookah = isHookahMenuItem(item)
  const optionCopy = getOptionCopy(isHookah)
  const shouldRenderOptions = itemOptions.length > 0 || (isHookah && canManage)
  const canAddOptions = canManage && (isHookah || itemOptions.length > 0)
  const canApplyBaseFlavorProfiles = isHookah && canManage && (item.missingBaseFlavorProfilesCount ?? 0) > 0

  const row = el('div', { className: 'venue-menu-item' })
  row.dataset.itemId = String(item.id)
  const feedback = buildMutationFeedback()

  const renderReadOnly = () => {
    const info = el('div', { className: 'venue-menu-item-info' })
    const price = el('span', {
      className: 'venue-menu-item-price',
      text: formatPrice(item.priceMinor, item.currency)
    })
    const name = el('strong', { text: item.name })
    name.dataset.menuFocus = 'true'
    name.tabIndex = -1
    name.setAttribute('role', 'heading')
    name.setAttribute('aria-level', '4')
    append(info, name, price)
    if (!item.isAvailable) info.appendChild(el('span', { className: 'menu-item-badge', text: 'Стоп-лист' }))

    const primaryActions = el('div', { className: 'venue-menu-item-actions venue-menu-item-primary-actions' })
    if (canManageAvailability) {
      const availabilityLabel = el('label', { className: 'venue-menu-option-toggle' })
      const availabilityInput = document.createElement('input')
      const availabilityText = el('span', {
        text: item.isAvailable ? 'Доступно гостям' : 'В стоп-листе'
      })
      availabilityInput.type = 'checkbox'
      availabilityInput.checked = item.isAvailable
      availabilityInput.dataset.menuControl = 'item-availability'
      availabilityInput.setAttribute(
        'aria-label',
        item.isAvailable ? `Доступно гостям: ${item.name}` : `В стоп-листе: ${item.name}`
      )
      availabilityInput.addEventListener('change', () => {
        availabilityInput.disabled = true
        handlers.onSetItemAvailability(item, availabilityInput.checked, (message) => {
          availabilityInput.checked = item.isAvailable
          availabilityInput.disabled = false
          showMutationFeedback(feedback, message)
        })
      })
      append(availabilityLabel, availabilityInput, availabilityText)
      primaryActions.appendChild(availabilityLabel)
    }
    if (canManage) {
      const editButton = el('button', { className: 'button-small', text: 'Править позицию' }) as HTMLButtonElement
      editButton.dataset.menuControl = 'item-edit'
      editButton.addEventListener('click', renderEditForm)
      primaryActions.appendChild(editButton)
    }

    const optionSection = shouldRenderOptions
      ? renderOptionSection()
      : null
    const secondaryActions = el('div', { className: 'venue-menu-item-actions venue-menu-item-secondary-actions' })
    if (canManage) {
      const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
      const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement
      const deleteButton = el('button', {
        className: 'button-small button-danger',
        text: 'Удалить'
      }) as HTMLButtonElement
      upButton.dataset.menuControl = 'item-move-up'
      downButton.dataset.menuControl = 'item-move-down'
      deleteButton.dataset.menuControl = 'item-delete'
      upButton.addEventListener('click', () => handlers.onMoveItem(item, 'up', (message) => showMutationFeedback(feedback, message)))
      downButton.addEventListener('click', () => handlers.onMoveItem(item, 'down', (message) => showMutationFeedback(feedback, message)))
      deleteButton.addEventListener('click', () => handlers.onDeleteItem(item, (message) => showMutationFeedback(feedback, message)))
      append(secondaryActions, upButton, downButton, deleteButton)
    }

    row.replaceChildren(info, primaryActions)
    if (optionSection) row.appendChild(optionSection)
    append(row, secondaryActions, feedback)
  }

  const renderOptionSection = () => {
    const optionSection = el('div', { className: 'venue-menu-option-section' })
    const optionHeader = el('div', { className: 'venue-menu-option-header' })
    optionHeader.appendChild(el('span', { className: 'venue-menu-options-title', text: optionCopy.title }))
    const addActions = el('div', { className: 'venue-inline-actions' })
    if (canApplyBaseFlavorProfiles) {
      const applyBaseButton = el('button', { className: 'button-small', text: 'Добавить базовые вкусы' }) as HTMLButtonElement
      applyBaseButton.dataset.menuControl = 'item-add-base-flavors'
      applyBaseButton.addEventListener('click', () =>
        handlers.onApplyBaseFlavorProfiles(item, (message) => showMutationFeedback(feedback, message))
      )
      addActions.appendChild(applyBaseButton)
    }
    let addForm: HTMLFormElement | null = null
    if (canAddOptions) {
      const addOptionButton = el('button', { className: 'button-small', text: optionCopy.addButton }) as HTMLButtonElement
      addOptionButton.type = 'button'
      addOptionButton.dataset.menuControl = 'item-create-option'
      addOptionButton.addEventListener('click', () => {
        if (addForm) {
          addForm.hidden = !addForm.hidden
          if (!addForm.hidden) addForm.querySelector<HTMLInputElement>('input')?.focus()
          return
        }
        addForm = el('form', { className: 'venue-menu-inline-form venue-menu-option-create-form' })
        const name = buildField(optionCopy.nameLabel, optionCopy.nameLabel)
        const price = buildPriceField(optionCopy.priceLabel, 'Например 150')
        const formActions = el('div', { className: 'venue-inline-actions' })
        const save = el('button', { className: 'button-small', text: optionCopy.addButton }) as HTMLButtonElement
        const cancel = el('button', {
          className: 'button-small button-secondary',
          text: 'Отменить'
        }) as HTMLButtonElement
        save.type = 'submit'
        cancel.type = 'button'
        cancel.addEventListener('click', () => {
          if (addForm) addForm.hidden = true
          focusConnectedMenuTarget(
            addOptionButton,
            row.querySelector<HTMLElement>('[data-menu-focus="true"]')
          )
        })
        addForm.addEventListener('submit', (event) => {
          event.preventDefault()
          const priceDeltaMinor = parseOptionPriceDeltaMinor(price.input.value)
          const trimmed = name.input.value.trim()
          if (!trimmed || priceDeltaMinor === null) {
            showMutationFeedback(feedback, 'Проверьте название и доплату.')
            return
          }
          feedback.hidden = true
          handlers.onCreateOption(item, trimmed, priceDeltaMinor, (message) => showMutationFeedback(feedback, message))
        })
        append(formActions, save, cancel)
        append(addForm, name.field, price.field, formActions)
        optionSection.insertBefore(addForm, optionsList)
        name.input.focus()
      })
      addActions.appendChild(addOptionButton)
    }
    if (addActions.childElementCount > 0) optionHeader.appendChild(addActions)

    const optionsList = el('div', { className: 'venue-menu-options' })
    if (!itemOptions.length) {
      optionsList.appendChild(el('p', { className: 'venue-empty', text: optionCopy.emptyText }))
    } else {
      itemOptions.forEach((option) => {
        optionsList.appendChild(
          renderOptionRow(option, item.name, item.currency, canManage, canManageAvailability, optionCopy, handlers)
        )
      })
    }
    append(optionSection, optionHeader, optionsList)
    return optionSection
  }

  const renderEditForm = () => {
    const form = el('form', { className: 'venue-menu-inline-form venue-menu-item-edit-form' })
    const name = buildField('Название позиции', 'Название позиции', item.name)
    const price = buildPriceField('Цена, ₽', 'Например 350', String(item.priceMinor / 100))
    const actions = el('div', { className: 'venue-inline-actions' })
    const save = el('button', { className: 'button-small', text: 'Сохранить' }) as HTMLButtonElement
    const cancel = el('button', {
      className: 'button-small button-secondary',
      text: 'Отменить'
    }) as HTMLButtonElement
    save.type = 'submit'
    cancel.type = 'button'
    cancel.addEventListener('click', () => {
      renderReadOnly()
      focusMenuControl(row, 'item-edit', '[data-menu-focus="true"]')
    })
    form.addEventListener('submit', (event) => {
      event.preventDefault()
      const priceMinor = parsePriceMinor(price.input.value)
      const trimmed = name.input.value.trim()
      if (!trimmed || priceMinor === null) {
        showMutationFeedback(feedback, 'Заполните название и цену.')
        return
      }
      feedback.hidden = true
      handlers.onEditItem(item, trimmed, priceMinor, (message) => showMutationFeedback(feedback, message))
    })
    append(actions, save, cancel)
    append(form, name.field, price.field, actions)
    row.replaceChildren(form, feedback)
    name.input.focus()
  }

  renderReadOnly()
  return row
}

function renderCategoryCard(
  category: VenueMenuCategoryDto,
  canManage: boolean,
  canManageAvailability: boolean,
  expanded: boolean,
  handlers: {
    onExpandedChange: (categoryId: number, expanded: boolean) => void
    onRename: (category: VenueMenuCategoryDto, name: string, onFailure?: MutationFailureHandler) => void
    onDelete: (category: VenueMenuCategoryDto, onFailure?: MutationFailureHandler) => void
    onMoveCategory: (
      category: VenueMenuCategoryDto,
      direction: 'up' | 'down',
      onFailure?: MutationFailureHandler
    ) => void
    onCreateItem: (
      category: VenueMenuCategoryDto,
      name: string,
      priceMinor: number,
      currency: string,
      onFailure?: MutationFailureHandler
    ) => void
  } & MenuMutationHandlers
) {
  const card = el('details', { className: 'venue-menu-category' })
  card.dataset.categoryId = String(category.id)
  card.open = expanded
  card.addEventListener('toggle', () => handlers.onExpandedChange(category.id, card.open))
  const summary = el('summary', { className: 'venue-menu-category-summary' })
  summary.dataset.menuControl = 'category-summary'
  summary.dataset.menuCategorySummary = String(category.id)
  const title = el('span', { className: 'venue-menu-category-title', text: category.name })
  title.setAttribute('role', 'heading')
  title.setAttribute('aria-level', '3')
  const itemCount = el('span', {
    className: 'venue-menu-category-count',
    text: `Позиции: ${category.items.length}`
  })
  append(summary, title, itemCount)

  const body = el('div', { className: 'venue-menu-category-body' })
  const header = el('div', { className: 'venue-menu-category-toolbar' })
  const controls = el('div', { className: 'venue-inline-actions' })
  const feedback = buildMutationFeedback()
  const renameButton = el('button', { className: 'button-small', text: 'Переименовать' }) as HTMLButtonElement
  const deleteButton = el('button', { className: 'button-small button-danger', text: 'Удалить' }) as HTMLButtonElement
  const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
  const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement
  renameButton.dataset.menuControl = 'category-rename'
  deleteButton.dataset.menuControl = 'category-delete'
  upButton.dataset.menuControl = 'category-move-up'
  downButton.dataset.menuControl = 'category-move-down'

  let renameForm: HTMLFormElement | null = null

  const list = el('div', { className: 'venue-menu-items' })
  const showRenameForm = () => {
    if (renameForm) {
      renameForm.hidden = false
      renameForm.querySelector<HTMLInputElement>('input')?.focus()
      return
    }
    renameForm = el('form', { className: 'venue-menu-inline-form venue-menu-category-rename-form' })
    const name = buildField('Название категории', 'Название категории', category.name)
    const actions = el('div', { className: 'venue-inline-actions' })
    const save = el('button', { className: 'button-small', text: 'Сохранить' }) as HTMLButtonElement
    const cancel = el('button', {
      className: 'button-small button-secondary',
      text: 'Отменить'
    }) as HTMLButtonElement
    save.type = 'submit'
    cancel.type = 'button'
    cancel.addEventListener('click', () => {
      if (renameForm) renameForm.hidden = true
      focusConnectedMenuTarget(renameButton, summary)
    })
    renameForm.addEventListener('submit', (event) => {
      event.preventDefault()
      const trimmed = name.input.value.trim()
      if (!trimmed) {
        showMutationFeedback(feedback, 'Имя не может быть пустым.')
        return
      }
      feedback.hidden = true
      handlers.onRename(category, trimmed, (message) => showMutationFeedback(feedback, message))
    })
    append(actions, save, cancel)
    append(renameForm, name.field, actions)
    body.insertBefore(renameForm, list)
    name.input.focus()
  }

  renameButton.addEventListener('click', showRenameForm)
  deleteButton.addEventListener('click', () => {
    if (!window.confirm('Удалить категорию? Она должна быть пустой.')) return
    handlers.onDelete(category, (message) => showMutationFeedback(feedback, message))
  })
  upButton.addEventListener('click', () =>
    handlers.onMoveCategory(category, 'up', (message) => showMutationFeedback(feedback, message))
  )
  downButton.addEventListener('click', () =>
    handlers.onMoveCategory(category, 'down', (message) => showMutationFeedback(feedback, message))
  )

  if (canManage) {
    append(controls, renameButton, upButton, downButton, deleteButton)
    header.appendChild(controls)
  }

  if (!category.items.length) {
    list.appendChild(el('p', { className: 'venue-empty', text: 'Пусто.' }))
  }
  category.items.forEach((item) => {
    list.appendChild(
      renderItemRow(
        item,
        canManage,
        canManageAvailability,
        handlers
      )
    )
  })

  const createRow = el('form', { className: 'venue-form-row venue-menu-item-create-form' })
  const name = buildField('Название позиции', 'Название позиции')
  const price = buildPriceField('Цена, ₽', 'Например 350')
  const currencySelect = document.createElement('select')
  currencySelect.className = 'venue-select'
  currencySelect.setAttribute('aria-label', 'Валюта новой позиции')
  currencySelect.appendChild(new Option(DEFAULT_CURRENCY, DEFAULT_CURRENCY))
  const createButton = el('button', { className: 'button-small', text: 'Добавить позицию' }) as HTMLButtonElement
  const createFeedback = buildMutationFeedback()
  createButton.type = 'submit'
  createRow.addEventListener('submit', (event) => {
    event.preventDefault()
    const priceMinor = parsePriceMinor(price.input.value)
    const trimmed = name.input.value.trim()
    if (!trimmed || priceMinor === null) {
      showMutationFeedback(createFeedback, 'Заполните название и цену.')
      return
    }
    createFeedback.hidden = true
    handlers.onCreateItem(category, trimmed, priceMinor, currencySelect.value, (message) =>
      showMutationFeedback(createFeedback, message)
    )
  })
  append(createRow, name.field, price.field, currencySelect, createButton, createFeedback)

  if (canManage) {
    body.appendChild(header)
  }
  body.appendChild(list)
  if (canManage) {
    body.appendChild(createRow)
  }
  body.appendChild(feedback)
  append(card, summary, body)
  return card
}

export function renderVenueMenuScreen(options: VenueMenuOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const canView = access.permissions.includes('MENU_VIEW')
  const canManage = access.permissions.includes('MENU_MANAGE')
  const canManageAvailability = access.permissions.includes('MENU_AVAILABILITY_MANAGE')
  const canShiftCheck =
    canView && access.role !== 'STAFF' && access.permissions.includes('MENU_SHIFT_CHECK')
  const refs = buildMenuDom(root, canShiftCheck)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let confirmAbort: AbortController | null = null
  let loadSeq = 0
  let interactionGeneration = 0
  let restoreGeneration = 0
  let programmaticRestoreDepth = 0
  let programmaticScrollPosition: { x: number; y: number } | null = null
  let activeInteractionScrollFrame: number | null = null
  let menu: VenueMenuCategoryDto[] = []
  let shiftFilter: ShiftCheckFilter = 'all'
  let shiftSearch = ''
  let shiftMassMode = false
  let shiftConfirming = false
  let shiftNeedsRefresh = false
  const expandedCategoryIds = new Set<number>()
  const draftItemAvailability = new Map<number, boolean>()
  const draftOptionAvailability = new Map<number, { itemId: number; isAvailable: boolean }>()
  const selectedItemIds = new Set<number>()
  const selectedOptionIds = new Set<number>()
  let filteredItemIds: number[] = []
  let filteredOptionIds: number[] = []
  if (!canManage) {
    refs.createCategoryAction.remove()
    refs.createCategoryForm.remove()
  }

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const clearMenuSuccess = () => {
    programmaticRestoreDepth += 1
    try {
      refs.success.textContent = ''
      refs.success.hidden = true
      programmaticScrollPosition = { x: window.scrollX, y: window.scrollY }
    } finally {
      programmaticRestoreDepth -= 1
    }
  }

  const announceMenuSuccess = (context: MenuRestoreContext, text: string) => {
    if (disposed || context.restoreGeneration !== restoreGeneration) return
    programmaticRestoreDepth += 1
    try {
      refs.success.hidden = false
      refs.success.textContent = text
      programmaticScrollPosition = { x: window.scrollX, y: window.scrollY }
    } finally {
      programmaticRestoreDepth -= 1
    }
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
          action.label === 'Повторить' ? { ...action, onClick: () => void loadMenu() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void loadMenu() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const findItemById = (itemId: number) =>
    menu.flatMap((category) => category.items).find((item) => item.id === itemId) ?? null

  const getOptionCopyForOption = (option: VenueMenuOptionDto) => {
    const ownerItem = findItemById(option.itemId)
    return getOptionCopy(ownerItem ? isHookahMenuItem(ownerItem) : false)
  }

  const findOptionById = (optionId: number) => {
    for (const category of menu) {
      for (const item of category.items) {
        const option = getScopedOptions(item).find((candidate) => candidate.id === optionId)
        if (option) return option
      }
    }
    return null
  }

  type MenuRestoreContext = {
    interactionGeneration: number
    restoreGeneration: number
    categoryId?: number | null
    itemId?: number | null
    optionId?: number | null
    anchorSelector?: string | null
    anchorTop?: number | null
    focusSelector?: string | null
  }

  const categorySummarySelector = (categoryId: number) =>
    `.venue-menu-category[data-category-id="${categoryId}"] > ` +
    `summary[data-menu-category-summary="${categoryId}"]`

  const getCategoryIdForItem = (itemId: number) =>
    menu.find((category) => category.items.some((item) => item.id === itemId))?.id ?? null

  const findMenuAnchor = (context: MenuRestoreContext) => {
    const selectors = [
      context.optionId != null ? `.venue-menu-option[data-option-id="${context.optionId}"]` : null,
      context.itemId != null ? `.venue-menu-item[data-item-id="${context.itemId}"]` : null,
      context.anchorSelector ?? null,
      context.categoryId != null ? `.venue-menu-category[data-category-id="${context.categoryId}"]` : null
    ].filter((selector): selector is string => Boolean(selector))
    for (const selector of selectors) {
      const target = refs.categories.querySelector<HTMLElement>(selector)
      if (target) return { selector, target }
    }
    return null
  }

  const captureMenuContext = (
    context: Omit<
      MenuRestoreContext,
      'interactionGeneration' | 'restoreGeneration' | 'anchorTop'
    > = {}
  ) => {
    restoreGeneration += 1
    clearMenuSuccess()
    const categoryId =
      context.categoryId ??
      (context.itemId != null ? getCategoryIdForItem(context.itemId) : null) ??
      (context.optionId != null ? getCategoryIdForItem(findOptionById(context.optionId)?.itemId ?? -1) : null)
    if (categoryId != null) expandedCategoryIds.add(categoryId)
    const anchor = findMenuAnchor({
      ...context,
      interactionGeneration,
      restoreGeneration,
      categoryId
    })
    return {
      ...context,
      interactionGeneration,
      restoreGeneration,
      categoryId,
      anchorSelector: context.anchorSelector ?? anchor?.selector ?? null,
      anchorTop: anchor?.target.getBoundingClientRect().top ?? null
    } satisfies MenuRestoreContext
  }

  const canRestoreMenuContext = (context?: MenuRestoreContext | null): context is MenuRestoreContext =>
    Boolean(
      context &&
        !disposed &&
        context.restoreGeneration === restoreGeneration &&
        context.interactionGeneration === interactionGeneration
    )

  const restoreMenuContext = (context?: MenuRestoreContext | null) => {
    if (!canRestoreMenuContext(context)) return
    if (context.categoryId != null) expandedCategoryIds.add(context.categoryId)
    const anchor = findMenuAnchor(context)
    if (!anchor) return
    if (!canRestoreMenuContext(context)) return
    programmaticRestoreDepth += 1
    try {
      if (context.anchorTop != null) {
        window.scrollBy({ top: anchor.target.getBoundingClientRect().top - context.anchorTop, behavior: 'auto' })
      } else {
        anchor.target.scrollIntoView({ block: 'nearest', inline: 'nearest' })
      }
      const focusSelector = context.focusSelector ?? '[data-menu-focus="true"]'
      const focusTarget = anchor.target.matches(focusSelector)
        ? anchor.target
        : anchor.target.querySelector<HTMLElement>(focusSelector)
      if (focusTarget) focusTarget.focus({ preventScroll: true })
      programmaticScrollPosition = { x: window.scrollX, y: window.scrollY }
    } finally {
      programmaticRestoreDepth -= 1
    }
  }

  type MenuFormKind =
    | 'category-rename'
    | 'item-create'
    | 'item-edit'
    | 'option-create'
    | 'option-edit'

  type MenuFormControlState = {
    value: string
    checked: boolean | null
    selectionStart: number | null
    selectionEnd: number | null
  }

  type MenuActiveInteractionContext = {
    interactionGeneration: number
    restoreGeneration: number
    scrollX: number
    scrollY: number
    categoryId: number | null
    categoryExpanded: boolean | null
    itemId: number | null
    optionId: number | null
    formKind: MenuFormKind | null
    formControls: MenuFormControlState[]
    focusedFormControlIndex: number
    focusedFormButtonIndex: number
    focusControl: string | null
  }

  type MenuEditableControl = HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement

  const readMenuEntityId = (node: HTMLElement | null, key: 'categoryId' | 'itemId' | 'optionId') => {
    const raw = node?.dataset[key]
    if (!raw) return null
    const value = Number(raw)
    return Number.isFinite(value) ? value : null
  }

  const getMenuFormKind = (form: HTMLFormElement | null): MenuFormKind | null => {
    if (!form) return null
    if (form.classList.contains('venue-menu-category-rename-form')) return 'category-rename'
    if (form.classList.contains('venue-menu-item-create-form')) return 'item-create'
    if (form.classList.contains('venue-menu-item-edit-form')) return 'item-edit'
    if (form.classList.contains('venue-menu-option-create-form')) return 'option-create'
    if (form.classList.contains('venue-menu-option-edit-form')) return 'option-edit'
    return null
  }

  const captureActiveMenuInteraction = (): MenuActiveInteractionContext => {
    const active = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dynamicActive = active && refs.categories.contains(active) ? active : null
    const categoryNode = dynamicActive?.closest<HTMLElement>('.venue-menu-category') ?? null
    const itemNode = dynamicActive?.closest<HTMLElement>('.venue-menu-item') ?? null
    const optionNode = dynamicActive?.closest<HTMLElement>('.venue-menu-option') ?? null
    const form = dynamicActive?.closest<HTMLFormElement>('form') ?? null
    const formKind = getMenuFormKind(form)
    const formControls = form
      ? Array.from(form.querySelectorAll<MenuEditableControl>('input, select, textarea'))
      : []
    const formButtons = form ? Array.from(form.querySelectorAll<HTMLButtonElement>('button')) : []

    return {
      interactionGeneration,
      restoreGeneration,
      scrollX: window.scrollX,
      scrollY: window.scrollY,
      categoryId: readMenuEntityId(categoryNode, 'categoryId'),
      categoryExpanded: categoryNode instanceof HTMLDetailsElement ? categoryNode.open : null,
      itemId: readMenuEntityId(itemNode, 'itemId'),
      optionId: readMenuEntityId(optionNode, 'optionId'),
      formKind,
      formControls: formControls.map((control) => ({
        value: control.value,
        checked: control instanceof HTMLInputElement ? control.checked : null,
        selectionStart:
          control instanceof HTMLInputElement || control instanceof HTMLTextAreaElement
            ? control.selectionStart
            : null,
        selectionEnd:
          control instanceof HTMLInputElement || control instanceof HTMLTextAreaElement
            ? control.selectionEnd
            : null
      })),
      focusedFormControlIndex: dynamicActive ? formControls.indexOf(dynamicActive as MenuEditableControl) : -1,
      focusedFormButtonIndex: dynamicActive ? formButtons.indexOf(dynamicActive as HTMLButtonElement) : -1,
      focusControl:
        dynamicActive?.dataset.menuControl ??
        (dynamicActive?.dataset.menuFocus === 'true' ? 'entity-heading' : null)
    }
  }

  const findActiveInteractionScope = (context: MenuActiveInteractionContext) => {
    if (context.optionId != null) {
      const option = refs.categories.querySelector<HTMLElement>(
        `.venue-menu-option[data-option-id="${context.optionId}"]`
      )
      if (option) return option
    }
    if (context.itemId != null) {
      const item = refs.categories.querySelector<HTMLElement>(
        `.venue-menu-item[data-item-id="${context.itemId}"]`
      )
      if (item) return item
    }
    if (context.categoryId != null) {
      return refs.categories.querySelector<HTMLElement>(
        `.venue-menu-category[data-category-id="${context.categoryId}"]`
      )
    }
    return null
  }

  const restoreActiveMenuInteraction = (context: MenuActiveInteractionContext) => {
    if (
      disposed ||
      context.interactionGeneration !== interactionGeneration ||
      context.restoreGeneration !== restoreGeneration
    ) {
      refs.categories.style.overflowAnchor = ''
      return
    }
    programmaticRestoreDepth += 1
    try {
      let scope = findActiveInteractionScope(context)
      let form: HTMLFormElement | null = null
      if (scope && context.formKind) {
        const triggerKey = {
          'category-rename': 'category-rename',
          'item-create': null,
          'item-edit': 'item-edit',
          'option-create': 'item-create-option',
          'option-edit': 'option-edit'
        } satisfies Record<MenuFormKind, string | null>
        const formSelector = {
          'category-rename': '.venue-menu-category-rename-form',
          'item-create': '.venue-menu-item-create-form',
          'item-edit': '.venue-menu-item-edit-form',
          'option-create': '.venue-menu-option-create-form',
          'option-edit': '.venue-menu-option-edit-form'
        } satisfies Record<MenuFormKind, string>
        const trigger = triggerKey[context.formKind]
          ? scope.querySelector<HTMLElement>(`[data-menu-control="${triggerKey[context.formKind]}"]`)
          : null
        trigger?.click()
        scope = findActiveInteractionScope(context)
        form = scope?.querySelector<HTMLFormElement>(formSelector[context.formKind]) ?? null
      }

      if (form) {
        const controls = Array.from(form.querySelectorAll<MenuEditableControl>('input, select, textarea'))
        const buttons = Array.from(form.querySelectorAll<HTMLButtonElement>('button'))
        context.formControls.forEach((state, index) => {
          const control = controls[index]
          if (!control) return
          control.value = state.value
          if (control instanceof HTMLInputElement && state.checked != null) {
            control.checked = state.checked
          }
        })
        const focusedControl = controls[context.focusedFormControlIndex]
        const focusedButton = buttons[context.focusedFormButtonIndex]
        const focusTarget = focusedControl ?? focusedButton ?? null
        focusTarget?.focus({ preventScroll: true })
        if (
          focusedControl &&
          (focusedControl instanceof HTMLInputElement || focusedControl instanceof HTMLTextAreaElement)
        ) {
          const state = context.formControls[context.focusedFormControlIndex]
          if (state?.selectionStart != null && state.selectionEnd != null) {
            focusedControl.setSelectionRange(state.selectionStart, state.selectionEnd)
          }
        }
      } else if (scope && context.focusControl) {
        const focusTarget =
          context.focusControl === 'entity-heading'
            ? scope.querySelector<HTMLElement>('[data-menu-focus="true"]')
            : scope.querySelector<HTMLElement>(`[data-menu-control="${context.focusControl}"]`)
        focusTarget?.focus({ preventScroll: true })
      }

      window.scrollTo({ left: context.scrollX, top: context.scrollY, behavior: 'auto' })
      programmaticScrollPosition = { x: window.scrollX, y: window.scrollY }
    } finally {
      programmaticRestoreDepth -= 1
    }
    if (activeInteractionScrollFrame != null) {
      window.cancelAnimationFrame(activeInteractionScrollFrame)
    }
    const isCurrentInteraction = () =>
      !disposed &&
      context.interactionGeneration === interactionGeneration &&
      context.restoreGeneration === restoreGeneration
    let protectedFramesRemaining = 2
    let stableFrames = 0
    let remainingFrames = 30
    const restoreAfterLayout = () => {
      activeInteractionScrollFrame = window.requestAnimationFrame(() => {
        if (!isCurrentInteraction()) {
          activeInteractionScrollFrame = null
          refs.categories.style.overflowAnchor = ''
          return
        }
        const scrollIsStable =
          Math.abs(window.scrollX - context.scrollX) <= 0.5 &&
          Math.abs(window.scrollY - context.scrollY) <= 0.5
        programmaticRestoreDepth += 1
        try {
          window.scrollTo({ left: context.scrollX, top: context.scrollY, behavior: 'auto' })
          programmaticScrollPosition = { x: window.scrollX, y: window.scrollY }
        } finally {
          programmaticRestoreDepth -= 1
        }
        if (protectedFramesRemaining > 0) {
          protectedFramesRemaining -= 1
          stableFrames = 0
          if (protectedFramesRemaining === 0) refs.categories.style.overflowAnchor = ''
        } else {
          stableFrames = scrollIsStable ? stableFrames + 1 : 0
        }
        remainingFrames -= 1
        if (stableFrames >= 2 || remainingFrames <= 0) {
          activeInteractionScrollFrame = null
          refs.categories.style.overflowAnchor = ''
          return
        }
        restoreAfterLayout()
      })
    }
    // Replacing a focused form can trigger browser scroll anchoring across multiple layout frames.
    restoreAfterLayout()
  }

  const mutationErrorMessage = (error: ApiErrorInfo) => presentApiError(error, { isDebug, scope: 'venue' }).message

  const reportMutationFailure = (error: ApiErrorInfo, onFailure?: MutationFailureHandler) => {
    showError(error)
    onFailure?.(mutationErrorMessage(error))
  }

  const refreshAfterMutation = async (context: MenuRestoreContext, successMessage: string) => {
    announceMenuSuccess(context, successMessage)
    await loadMenu({ restoreContext: context })
  }

  const effectiveItemAvailability = (item: VenueMenuItemDto) =>
    draftItemAvailability.get(item.id) ?? item.isAvailable

  const effectiveOptionAvailability = (option: VenueMenuOptionDto) =>
    draftOptionAvailability.get(option.id)?.isAvailable ?? option.isAvailable

  const setItemDraft = (item: VenueMenuItemDto, isAvailable: boolean) => {
    if (item.isAvailable === isAvailable) {
      draftItemAvailability.delete(item.id)
    } else {
      draftItemAvailability.set(item.id, isAvailable)
    }
  }

  const setOptionDraft = (option: VenueMenuOptionDto, isAvailable: boolean) => {
    if (option.isAvailable === isAvailable) {
      draftOptionAvailability.delete(option.id)
    } else {
      draftOptionAvailability.set(option.id, { itemId: option.itemId, isAvailable })
    }
  }

  const clearShiftDraft = () => {
    draftItemAvailability.clear()
    draftOptionAvailability.clear()
    selectedItemIds.clear()
    selectedOptionIds.clear()
    filteredItemIds = []
    filteredOptionIds = []
    shiftMassMode = false
    shiftFilter = 'all'
    shiftSearch = ''
    if (refs.shiftCheck) {
      refs.shiftCheck.searchInput.value = ''
    }
    shiftNeedsRefresh = false
  }

  const rebaseShiftDraft = (
    nextMenu: VenueMenuCategoryDto[],
    previousItemDrafts: Map<number, boolean>,
    previousOptionDrafts: Map<number, { itemId: number; isAvailable: boolean }>
  ) => {
    const nextItems = new Map<number, VenueMenuItemDto>()
    const nextOptions = new Map<number, VenueMenuOptionDto>()
    nextMenu.forEach((category) => {
      category.items.forEach((item) => {
        nextItems.set(item.id, item)
        getScopedOptions(item).forEach((option) => nextOptions.set(option.id, option))
      })
    })

    draftItemAvailability.clear()
    previousItemDrafts.forEach((desired, itemId) => {
      const item = nextItems.get(itemId)
      if (item && item.isAvailable !== desired) {
        draftItemAvailability.set(itemId, desired)
      }
    })

    draftOptionAvailability.clear()
    previousOptionDrafts.forEach((draft, optionId) => {
      const option = nextOptions.get(optionId)
      if (option && option.itemId === draft.itemId && option.isAvailable !== draft.isAvailable) {
        draftOptionAvailability.set(optionId, draft)
      }
    })

    for (const itemId of selectedItemIds) {
      if (!nextItems.has(itemId)) selectedItemIds.delete(itemId)
    }
    for (const optionId of selectedOptionIds) {
      if (!nextOptions.has(optionId)) selectedOptionIds.delete(optionId)
    }
  }

  const shiftRowMatchesFilter = (isAvailable: boolean, isDirty: boolean) => {
    if (shiftFilter === 'unavailable') return !isAvailable
    if (shiftFilter === 'dirty') return isDirty
    return true
  }

  const buildShiftSelection = (
    labelText: string,
    checked: boolean,
    onChange: (checked: boolean) => void
  ) => {
    const label = el('label', { className: 'venue-shift-check-select' })
    const input = document.createElement('input')
    input.type = 'checkbox'
    input.checked = checked
    input.setAttribute('aria-label', labelText)
    input.addEventListener('change', () => onChange(input.checked))
    label.appendChild(input)
    return label
  }

  const buildShiftAvailabilityToggle = (
    labelText: string,
    isAvailable: boolean,
    onChange: (checked: boolean) => void
  ) => {
    const stateText = isAvailable ? 'В наличии' : 'Нет в наличии'
    const button = el('button', {
      className: 'venue-shift-check-availability-switch',
      text: stateText
    }) as HTMLButtonElement
    button.type = 'button'
    button.setAttribute('role', 'switch')
    button.setAttribute('aria-checked', String(isAvailable))
    button.setAttribute('aria-label', `${labelText}: ${stateText}`)
    button.addEventListener('click', () => onChange(!isAvailable))
    return button
  }

  const renderShiftCheck = (focusSelector?: string) => {
    const shiftRefs = refs.shiftCheck
    if (!shiftRefs) return

    Object.entries(shiftRefs.filterButtons).forEach(([filter, button]) => {
      const isActive = filter === shiftFilter
      button.dataset.active = String(isActive)
      button.setAttribute('aria-pressed', String(isActive))
    })

    let itemMadeAvailable = 0
    let itemMadeUnavailable = 0
    draftItemAvailability.forEach((isAvailable) => {
      if (isAvailable) itemMadeAvailable += 1
      else itemMadeUnavailable += 1
    })
    let optionMadeAvailable = 0
    let optionMadeUnavailable = 0
    draftOptionAvailability.forEach((draft) => {
      if (draft.isAvailable) optionMadeAvailable += 1
      else optionMadeUnavailable += 1
    })
    shiftRefs.itemMadeAvailable.textContent = String(itemMadeAvailable)
    shiftRefs.itemMadeUnavailable.textContent = String(itemMadeUnavailable)
    shiftRefs.optionMadeAvailable.textContent = String(optionMadeAvailable)
    shiftRefs.optionMadeUnavailable.textContent = String(optionMadeUnavailable)

    const allItems = menu.flatMap((category) => category.items)
    const allOptions = allItems.flatMap((item) => getScopedOptions(item))
    const dirtyCount = draftItemAvailability.size + draftOptionAvailability.size
    shiftRefs.availableItemCount.textContent = String(allItems.filter(effectiveItemAvailability).length)
    shiftRefs.totalItemCount.textContent = String(allItems.length)
    shiftRefs.availableOptionCount.textContent = String(allOptions.filter(effectiveOptionAvailability).length)
    shiftRefs.totalOptionCount.textContent = String(allOptions.length)
    shiftRefs.dirtyCount.textContent = String(dirtyCount)
    shiftRefs.dirtySummary.hidden = dirtyCount === 0
    shiftRefs.massModeButton.hidden = shiftMassMode
    shiftRefs.bulkToolbar.hidden = !shiftMassMode

    const normalizedQuery = shiftSearch.trim().toLocaleLowerCase('ru-RU')
    const visibleItemIds: number[] = []
    const visibleOptionIds: number[] = []
    shiftRefs.categories.replaceChildren()

    menu.forEach((category) => {
      const totalItems = category.items.length
      const allCategoryOptions = category.items.flatMap((item) => getScopedOptions(item))
      const availableItems = category.items.filter(effectiveItemAvailability).length
      const availableOptions = allCategoryOptions.filter(effectiveOptionAvailability).length

      const categoryCard = el('section', { className: 'venue-shift-check-category' })
      categoryCard.dataset.categoryId = String(category.id)
      const categoryHeader = el('div', { className: 'venue-shift-check-category-header' })
      const categoryHeading = el('div')
      append(
        categoryHeading,
        el('h4', { text: category.name }),
        el('p', {
          className: 'venue-order-sub',
          text:
            `Позиции: ${availableItems}/${totalItems} · ` +
            `Опции: ${availableOptions}/${allCategoryOptions.length}`
        })
      )
      const categoryActions = el('div', { className: 'venue-inline-actions' })
      const categoryAvailable = el('button', {
        className: 'button-small',
        text: 'Все позиции в наличии'
      }) as HTMLButtonElement
      const categoryUnavailable = el('button', {
        className: 'button-small button-secondary',
        text: 'Все позиции недоступны'
      }) as HTMLButtonElement
      const categoryOptionsAvailable = el('button', {
        className: 'button-small',
        text: 'Все опции в наличии'
      }) as HTMLButtonElement
      const categoryOptionsUnavailable = el('button', {
        className: 'button-small button-secondary',
        text: 'Все опции недоступны'
      }) as HTMLButtonElement
      categoryAvailable.dataset.shiftAction = 'items-available'
      categoryUnavailable.dataset.shiftAction = 'items-unavailable'
      categoryOptionsAvailable.dataset.shiftAction = 'options-available'
      categoryOptionsUnavailable.dataset.shiftAction = 'options-unavailable'
      categoryAvailable.disabled = shiftConfirming || shiftNeedsRefresh || totalItems === 0
      categoryUnavailable.disabled = shiftConfirming || shiftNeedsRefresh || totalItems === 0
      categoryOptionsAvailable.disabled =
        shiftConfirming || shiftNeedsRefresh || allCategoryOptions.length === 0
      categoryOptionsUnavailable.disabled =
        shiftConfirming || shiftNeedsRefresh || allCategoryOptions.length === 0
      categoryAvailable.addEventListener('click', () => {
        category.items.forEach((item) => setItemDraft(item, true))
        shiftRefs.status.textContent = ''
        renderShiftCheck(
          `.venue-shift-check-category[data-category-id="${category.id}"] ` +
            '[data-shift-action="items-available"]'
        )
      })
      categoryUnavailable.addEventListener('click', () => {
        category.items.forEach((item) => setItemDraft(item, false))
        shiftRefs.status.textContent = ''
        renderShiftCheck(
          `.venue-shift-check-category[data-category-id="${category.id}"] ` +
            '[data-shift-action="items-unavailable"]'
        )
      })
      categoryOptionsAvailable.addEventListener('click', () => {
        allCategoryOptions.forEach((option) => setOptionDraft(option, true))
        shiftRefs.status.textContent = ''
        renderShiftCheck(
          `.venue-shift-check-category[data-category-id="${category.id}"] ` +
            '[data-shift-action="options-available"]'
        )
      })
      categoryOptionsUnavailable.addEventListener('click', () => {
        allCategoryOptions.forEach((option) => setOptionDraft(option, false))
        shiftRefs.status.textContent = ''
        renderShiftCheck(
          `.venue-shift-check-category[data-category-id="${category.id}"] ` +
            '[data-shift-action="options-unavailable"]'
        )
      })
      append(
        categoryActions,
        categoryAvailable,
        categoryUnavailable,
        categoryOptionsAvailable,
        categoryOptionsUnavailable
      )
      append(categoryHeader, categoryHeading, categoryActions)

      const categoryItems = el('div', { className: 'venue-shift-check-items' })
      category.items.forEach((item) => {
        const itemNameMatches =
          !normalizedQuery || item.name.toLocaleLowerCase('ru-RU').includes(normalizedQuery)
        const itemIsDirty = draftItemAvailability.has(item.id)
        const itemIsAvailable = effectiveItemAvailability(item)
        const itemVisible =
          itemNameMatches && shiftRowMatchesFilter(itemIsAvailable, itemIsDirty)

        const visibleOptions = getScopedOptions(item).filter((option) => {
          const queryMatches =
            !normalizedQuery ||
            itemNameMatches ||
            option.name.toLocaleLowerCase('ru-RU').includes(normalizedQuery)
          return (
            queryMatches &&
            shiftRowMatchesFilter(
              effectiveOptionAvailability(option),
              draftOptionAvailability.has(option.id)
            )
          )
        })
        if (!itemVisible && visibleOptions.length === 0) return

        const itemGroup = el('div', { className: 'venue-shift-check-item-group' })
        itemGroup.dataset.itemId = String(item.id)
        if (itemVisible) {
          visibleItemIds.push(item.id)
          const itemRow = el('div', { className: 'venue-shift-check-item' })
          itemRow.dataset.itemId = String(item.id)
          itemRow.dataset.massMode = String(shiftMassMode)
          itemRow.dataset.selected = String(shiftMassMode && selectedItemIds.has(item.id))
          itemRow.dataset.dirty = String(itemIsDirty)
          const selection = shiftMassMode
            ? buildShiftSelection(
                `Выбрать ${item.name}`,
                selectedItemIds.has(item.id),
                (checked) => {
                  if (checked) selectedItemIds.add(item.id)
                  else selectedItemIds.delete(item.id)
                  renderShiftCheck(
                    `.venue-shift-check-item[data-item-id="${item.id}"] ` +
                      '.venue-shift-check-select input'
                  )
                }
              )
            : null
          const selectionInput = selection?.querySelector('input')
          if (selectionInput) selectionInput.disabled = shiftConfirming
          const itemInfo = el('div', { className: 'venue-shift-check-row-info' })
          itemInfo.appendChild(el('strong', { text: item.name }))
          if (itemIsDirty) {
            itemInfo.appendChild(el('span', { className: 'menu-item-badge', text: 'Изменено' }))
          }
          const availability = buildShiftAvailabilityToggle(
            `Позиция ${item.name}`,
            itemIsAvailable,
            (checked) => {
              setItemDraft(item, checked)
              shiftRefs.status.textContent = ''
              renderShiftCheck(
                `.venue-shift-check-item[data-item-id="${item.id}"] [role="switch"]`
              )
            }
          )
          availability.disabled = shiftConfirming || shiftNeedsRefresh
          append(itemRow, selection, itemInfo, availability)
          itemGroup.appendChild(itemRow)
        } else {
          itemGroup.appendChild(
            el('p', { className: 'venue-shift-check-item-context', text: `Позиция: ${item.name}` })
          )
        }

        if (visibleOptions.length > 0) {
          const optionsHeader = el('div', { className: 'venue-shift-check-options-header' })
          const optionsTitle = el('span', { className: 'venue-menu-options-title', text: 'Опции позиции' })
          const optionsActions = el('div', { className: 'venue-inline-actions' })
          const optionsAvailable = el('button', {
            className: 'button-small',
            text: 'Все опции позиции в наличии'
          }) as HTMLButtonElement
          const optionsUnavailable = el('button', {
            className: 'button-small button-secondary',
            text: 'Все опции позиции недоступны'
          }) as HTMLButtonElement
          optionsAvailable.dataset.shiftAction = 'item-options-available'
          optionsUnavailable.dataset.shiftAction = 'item-options-unavailable'
          const scopedOptions = getScopedOptions(item)
          optionsAvailable.disabled = shiftConfirming || shiftNeedsRefresh || scopedOptions.length === 0
          optionsUnavailable.disabled = shiftConfirming || shiftNeedsRefresh || scopedOptions.length === 0
          optionsAvailable.addEventListener('click', () => {
            scopedOptions.forEach((option) => setOptionDraft(option, true))
            shiftRefs.status.textContent = ''
            renderShiftCheck(
              `.venue-shift-check-item-group[data-item-id="${item.id}"] ` +
                '[data-shift-action="item-options-available"]'
            )
          })
          optionsUnavailable.addEventListener('click', () => {
            scopedOptions.forEach((option) => setOptionDraft(option, false))
            shiftRefs.status.textContent = ''
            renderShiftCheck(
              `.venue-shift-check-item-group[data-item-id="${item.id}"] ` +
                '[data-shift-action="item-options-unavailable"]'
            )
          })
          append(optionsActions, optionsAvailable, optionsUnavailable)
          append(optionsHeader, optionsTitle, optionsActions)

          const optionsList = el('div', { className: 'venue-shift-check-options' })
          visibleOptions.forEach((option) => {
            visibleOptionIds.push(option.id)
            const optionRow = el('div', { className: 'venue-shift-check-option' })
            optionRow.dataset.optionId = String(option.id)
            optionRow.dataset.massMode = String(shiftMassMode)
            optionRow.dataset.selected = String(shiftMassMode && selectedOptionIds.has(option.id))
            optionRow.dataset.dirty = String(draftOptionAvailability.has(option.id))
            const selection = shiftMassMode
              ? buildShiftSelection(
                  `Выбрать ${option.name}`,
                  selectedOptionIds.has(option.id),
                  (checked) => {
                    if (checked) selectedOptionIds.add(option.id)
                    else selectedOptionIds.delete(option.id)
                    renderShiftCheck(
                      `.venue-shift-check-option[data-option-id="${option.id}"] ` +
                        '.venue-shift-check-select input'
                    )
                  }
                )
              : null
            const selectionInput = selection?.querySelector('input')
            if (selectionInput) selectionInput.disabled = shiftConfirming
            const optionInfo = el('div', { className: 'venue-shift-check-row-info' })
            optionInfo.appendChild(el('span', { text: option.name }))
            if (draftOptionAvailability.has(option.id)) {
              optionInfo.appendChild(el('span', { className: 'menu-item-badge', text: 'Изменено' }))
            }
            const availability = buildShiftAvailabilityToggle(
              `Опция ${option.name}`,
              effectiveOptionAvailability(option),
              (checked) => {
                setOptionDraft(option, checked)
                shiftRefs.status.textContent = ''
                renderShiftCheck(
                  `.venue-shift-check-option[data-option-id="${option.id}"] [role="switch"]`
                )
              }
            )
            availability.disabled = shiftConfirming || shiftNeedsRefresh
            append(optionRow, selection, optionInfo, availability)
            optionsList.appendChild(optionRow)
          })
          append(itemGroup, optionsHeader, optionsList)
        }
        categoryItems.appendChild(itemGroup)
      })

      if (categoryItems.childElementCount > 0) {
        append(categoryCard, categoryHeader, categoryItems)
        shiftRefs.categories.appendChild(categoryCard)
      }
    })

    filteredItemIds = visibleItemIds
    filteredOptionIds = visibleOptionIds
    if (shiftRefs.categories.childElementCount === 0) {
      shiftRefs.categories.appendChild(
        el('p', { className: 'venue-empty', text: 'По заданным условиям ничего не найдено.' })
      )
    }

    const allFilteredSelected =
      visibleItemIds.length + visibleOptionIds.length > 0 &&
      visibleItemIds.every((itemId) => selectedItemIds.has(itemId)) &&
      visibleOptionIds.every((optionId) => selectedOptionIds.has(optionId))
    shiftRefs.selectFilteredButton.textContent = allFilteredSelected
      ? 'Снять выбор с отфильтрованных'
      : 'Выбрать все отфильтрованные'
    shiftRefs.selectFilteredButton.disabled =
      shiftConfirming || shiftNeedsRefresh || visibleItemIds.length + visibleOptionIds.length === 0
    const selectionCount = selectedItemIds.size + selectedOptionIds.size
    const hasSelection = selectionCount > 0
    shiftRefs.selectedCount.textContent = `Выбрано: ${selectionCount}`
    shiftRefs.massModeButton.disabled = shiftConfirming || shiftNeedsRefresh
    shiftRefs.selectedAvailableButton.disabled = shiftConfirming || shiftNeedsRefresh || !hasSelection
    shiftRefs.selectedUnavailableButton.disabled = shiftConfirming || shiftNeedsRefresh || !hasSelection
    shiftRefs.clearSelectionButton.disabled = shiftConfirming || !hasSelection
    shiftRefs.exitMassModeButton.disabled = shiftConfirming
    shiftRefs.confirmButton.disabled = shiftConfirming || shiftNeedsRefresh
    shiftRefs.cancelButton.disabled = shiftConfirming
    shiftRefs.confirmButton.textContent = shiftConfirming
      ? 'Подтверждаем…'
      : 'Подтвердить проверку'

    if (focusSelector) {
      const focusTarget = shiftRefs.details.querySelector<HTMLElement>(focusSelector)
      if (focusTarget) focusTarget.focus()
      else shiftRefs.searchInput.focus()
    }
  }

  const renderMenu = () => {
    refs.categories.replaceChildren()
    refs.editingCategoryCount.textContent = String(menu.length)
    refs.editingItemCount.textContent = String(
      menu.reduce((total, category) => total + category.items.length, 0)
    )
    const categoryIds = new Set(menu.map((category) => category.id))
    for (const categoryId of expandedCategoryIds) {
      if (!categoryIds.has(categoryId)) expandedCategoryIds.delete(categoryId)
    }
    if (!menu.length) {
      refs.categories.appendChild(el('p', { className: 'venue-empty', text: 'Категории не найдены.' }))
      renderShiftCheck()
      return
    }
    menu.forEach((category) => {
      refs.categories.appendChild(
        renderCategoryCard(
          category,
          canManage,
          canManageAvailability,
          expandedCategoryIds.has(category.id),
          {
          onExpandedChange: (categoryId, expanded) => {
            if (expanded) expandedCategoryIds.add(categoryId)
            else expandedCategoryIds.delete(categoryId)
          },
          onRename: async (target, name, onFailure) => {
            const summarySelector = categorySummarySelector(target.id)
            const context = captureMenuContext({
              categoryId: target.id,
              anchorSelector: summarySelector,
              focusSelector: summarySelector
            })
            const result = await venueUpdateCategory(
              backendUrl,
              { venueId, categoryId: target.id, body: { name } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Категория обновлена')
          },
          onDelete: async (target, onFailure) => {
            const targetIndex = menu.findIndex((category) => category.id === target.id)
            const fallbackCategory = menu[targetIndex + 1] ?? menu[targetIndex - 1] ?? null
            const context = captureMenuContext({
              categoryId: fallbackCategory?.id ?? null,
              focusSelector: ':scope > summary'
            })
            const result = await venueDeleteCategory(backendUrl, { venueId, categoryId: target.id }, deps)
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Категория удалена')
          },
          onMoveCategory: async (target, direction, onFailure) => {
            const idx = menu.findIndex((item) => item.id === target.id)
            if (idx < 0) return
            const nextIdx = direction === 'up' ? idx - 1 : idx + 1
            if (nextIdx < 0 || nextIdx >= menu.length) return
            const reordered = [...menu]
            const [moved] = reordered.splice(idx, 1)
            reordered.splice(nextIdx, 0, moved)
            const summarySelector = categorySummarySelector(target.id)
            const context = captureMenuContext({
              categoryId: target.id,
              anchorSelector: summarySelector,
              focusSelector: summarySelector
            })
            const result = await venueReorderCategories(
              backendUrl,
              { venueId, body: { categoryIds: reordered.map((item) => item.id) } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Порядок обновлён')
          },
          onCreateItem: async (target, name, priceMinor, currency, onFailure) => {
            const context = captureMenuContext({ categoryId: target.id })
            const result = await venueCreateItem(
              backendUrl,
              { venueId, body: { categoryId: target.id, name, priceMinor, currency, isAvailable: true } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(
              { ...context, itemId: result.data.id, focusSelector: '[data-menu-focus="true"]' },
              'Позиция добавлена'
            )
          },
          onEditItem: async (item, name, priceMinor, onFailure) => {
            const context = captureMenuContext({
              categoryId: item.categoryId,
              itemId: item.id,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueUpdateItem(
              backendUrl,
              { venueId, itemId: item.id, body: { name, priceMinor, currency: item.currency } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Позиция обновлена')
          },
          onDeleteItem: async (item, onFailure) => {
            if (
              !window.confirm(
                'Позиция будет удалена из меню.\n\n' +
                  'Ссылки на неё в условиях акций и списках подарков на выбор будут удалены автоматически.\n\n' +
                  'Если позиция используется как фиксированный подарок, удалить её нельзя, ' +
                  'пока подарок не будет заменён в акции.'
              )
            ) {
              return
            }
            const context = captureMenuContext({
              categoryId: item.categoryId,
              focusSelector: ':scope > summary'
            })
            const result = await venueDeleteItem(backendUrl, { venueId, itemId: item.id }, deps)
            if (disposed) return
            if (!result.ok) {
              if (
                normalizeErrorCode(result.error) ===
                ApiErrorCodes.MENU_ITEM_DELETE_BLOCKED_BY_FIXED_REWARD
              ) {
                reportMutationFailure(result.error, onFailure)
                return
              }
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Позиция удалена')
          },
          onSetItemAvailability: async (item, isAvailable, onFailure) => {
            const context = captureMenuContext({
              categoryId: item.categoryId,
              itemId: item.id,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueSetItemAvailability(
              backendUrl,
              { venueId, itemId: item.id, body: { isAvailable } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(
              context,
              isAvailable ? 'Позиция доступна гостям' : 'Позиция в стоп-листе'
            )
          },
          onMoveItem: async (item, direction, onFailure) => {
            const category = menu.find((cat) => cat.id === item.categoryId)
            if (!category) return
            const idx = category.items.findIndex((it) => it.id === item.id)
            const nextIdx = direction === 'up' ? idx - 1 : idx + 1
            if (idx < 0 || nextIdx < 0 || nextIdx >= category.items.length) return
            const reordered = [...category.items]
            const [moved] = reordered.splice(idx, 1)
            reordered.splice(nextIdx, 0, moved)
            const context = captureMenuContext({ categoryId: item.categoryId, itemId: item.id })
            const result = await venueReorderItems(
              backendUrl,
              { venueId, body: { categoryId: category.id, itemIds: reordered.map((it) => it.id) } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, 'Порядок позиций обновлён')
          },
          onCreateOption: async (item, name, priceDeltaMinor, onFailure) => {
            const context = captureMenuContext({ categoryId: item.categoryId, itemId: item.id })
            const result = await venueCreateOption(
              backendUrl,
              { venueId, body: { itemId: item.id, name, priceDeltaMinor, isAvailable: true } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(
              { ...context, optionId: result.data.id, focusSelector: '[data-menu-focus="true"]' },
              getOptionCopy(isHookahMenuItem(item)).addedToast
            )
          },
          onApplyBaseFlavorProfiles: async (item, onFailure) => {
            const context = captureMenuContext({
              categoryId: item.categoryId,
              itemId: item.id,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueApplyBaseFlavorProfiles(backendUrl, { venueId, itemId: item.id }, deps)
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(
              context,
              `Добавлено вкусов: ${result.data.addedCount}. Уже были: ${result.data.existingCount}.`
            )
          },
          onEditOption: async (option, name, priceDeltaMinor, onFailure) => {
            const context = captureMenuContext({
              itemId: option.itemId,
              optionId: option.id,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueUpdateOption(
              backendUrl,
              { venueId, optionId: option.id, body: { name, priceDeltaMinor } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, getOptionCopyForOption(option).updatedToast)
          },
          onDeleteOption: async (option, onFailure) => {
            const context = captureMenuContext({
              itemId: option.itemId,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueDeleteOption(backendUrl, { venueId, optionId: option.id }, deps)
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            await refreshAfterMutation(context, getOptionCopyForOption(option).deletedToast)
          },
          onSetOptionAvailability: async (option, isAvailable, onFailure) => {
            const context = captureMenuContext({
              itemId: option.itemId,
              optionId: option.id,
              focusSelector: '[data-menu-focus="true"]'
            })
            const result = await venueSetOptionAvailability(
              backendUrl,
              { venueId, optionId: option.id, body: { isAvailable } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              reportMutationFailure(result.error, onFailure)
              return
            }
            const copy = getOptionCopyForOption(option)
            await refreshAfterMutation(context, isAvailable ? copy.enabledToast : copy.disabledToast)
          }
          }
        )
      )
    })
    renderShiftCheck()
  }

  const loadMenu = async (
    options: { rebaseDraft?: boolean; shiftRefresh?: boolean; restoreContext?: MenuRestoreContext | null } = {}
  ): Promise<boolean> => {
    if (!canView) {
      refs.categories.replaceChildren(el('p', { className: 'venue-empty', text: 'Недостаточно прав для просмотра меню.' }))
      return false
    }
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetMenu(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return false
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return false
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return false
    }
    const previousItemDrafts = new Map(draftItemAvailability)
    const previousOptionDrafts = new Map(draftOptionAvailability)
    if (options.rebaseDraft === false) {
      clearShiftDraft()
    } else {
      rebaseShiftDraft(result.data.categories, previousItemDrafts, previousOptionDrafts)
    }
    const currentInteractionContext =
      options.restoreContext && !canRestoreMenuContext(options.restoreContext)
        ? captureActiveMenuInteraction()
        : null
    if (currentInteractionContext) refs.categories.style.overflowAnchor = 'none'
    menu = result.data.categories
    shiftNeedsRefresh = false
    if (canRestoreMenuContext(options.restoreContext) && options.restoreContext.categoryId != null) {
      expandedCategoryIds.add(options.restoreContext.categoryId)
    }
    if (currentInteractionContext?.categoryId != null && currentInteractionContext.categoryExpanded != null) {
      if (currentInteractionContext.categoryExpanded) {
        expandedCategoryIds.add(currentInteractionContext.categoryId)
      } else {
        expandedCategoryIds.delete(currentInteractionContext.categoryId)
      }
    }
    renderMenu()
    if (refs.shiftCheck && options.shiftRefresh) {
      refs.shiftCheck.status.textContent =
        'Проверка обновлена. Проверьте изменения и подтвердите ещё раз.'
    }
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
    if (currentInteractionContext) {
      restoreActiveMenuInteraction(currentInteractionContext)
    } else {
      restoreMenuContext(options.restoreContext)
    }
    return true
  }

  const showShiftCheckError = (error: ApiErrorInfo) => {
    const normalized = normalizeErrorCode(error)
    if (normalized === ApiErrorCodes.UNAUTHORIZED || normalized === ApiErrorCodes.INITDATA_INVALID) {
      clearSession()
    }

    let title = 'Ошибка'
    let message = 'Не удалось завершить проверку меню.'
    let severity: 'info' | 'warn' | 'error' = 'error'
    let actions: ApiErrorAction[] = [
      { label: 'Повторить', kind: 'primary', onClick: () => void submitShiftCheck() },
      {
        label: 'Обновить проверку',
        kind: 'secondary',
        onClick: () => void loadMenu({ rebaseDraft: true, shiftRefresh: true })
      }
    ]

    const isMissing =
      error.status === 404 ||
      error.code === 'MENU_SHIFT_CHECK_ITEM_NOT_FOUND' ||
      error.code === 'MENU_SHIFT_CHECK_OPTION_NOT_FOUND' ||
      error.code === 'MENU_SHIFT_CHECK_ENTITY_NOT_FOUND'
    if (error.code === ApiErrorCodes.MENU_SHIFT_CHECK_STALE || error.status === 409) {
      title = 'Меню изменилось'
      message = 'Меню изменилось. Обновите проверку и повторите подтверждение.'
      severity = 'warn'
      shiftNeedsRefresh = true
      actions = [
        {
          label: 'Обновить проверку',
          kind: 'primary',
          onClick: () => void loadMenu({ rebaseDraft: true, shiftRefresh: true })
        }
      ]
    } else if (normalized === ApiErrorCodes.FORBIDDEN) {
      title = 'Недостаточно прав'
      message = 'Недостаточно прав для проверки меню.'
      severity = 'warn'
      shiftNeedsRefresh = true
      actions = []
    } else if (isMissing) {
      title = 'Меню изменилось'
      message = 'Одна из позиций больше не существует. Обновите проверку.'
      severity = 'warn'
      shiftNeedsRefresh = true
      actions = [
        {
          label: 'Обновить проверку',
          kind: 'primary',
          onClick: () => void loadMenu({ rebaseDraft: true, shiftRefresh: true })
        }
      ]
    }

    refs.error.dataset.severity = severity
    refs.errorTitle.textContent = title
    refs.errorMessage.textContent = message
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
    renderShiftCheck()
  }

  async function submitShiftCheck() {
    const shiftRefs = refs.shiftCheck
    if (!shiftRefs || shiftConfirming || shiftNeedsRefresh) return

    const itemChanges = [...draftItemAvailability.entries()]
      .sort(([leftId], [rightId]) => leftId - rightId)
      .map(([itemId, desiredIsAvailable]) => {
        const item = findItemById(itemId)
        return item
          ? {
              itemId,
              expectedIsAvailable: item.isAvailable,
              desiredIsAvailable
            }
          : null
      })
    const optionChanges = [...draftOptionAvailability.entries()]
      .sort(([leftId], [rightId]) => leftId - rightId)
      .map(([optionId, draft]) => {
        const option = findOptionById(optionId)
        return option && option.itemId === draft.itemId
          ? {
              optionId,
              itemId: option.itemId,
              expectedIsAvailable: option.isAvailable,
              desiredIsAvailable: draft.isAvailable
            }
          : null
      })

    if (itemChanges.some((change) => change === null) || optionChanges.some((change) => change === null)) {
      showShiftCheckError({
        status: 404,
        code: 'MENU_SHIFT_CHECK_ENTITY_NOT_FOUND',
        message: ''
      })
      return
    }

    hideError()
    shiftConfirming = true
    shiftRefs.status.textContent = 'Завершаем проверку...'
    renderShiftCheck()
    confirmAbort?.abort()
    const controller = new AbortController()
    confirmAbort = controller
    const result = await venueCompleteMenuShiftCheck(
      backendUrl,
      {
        venueId,
        body: {
          items: itemChanges.filter((change) => change !== null),
          options: optionChanges.filter((change) => change !== null)
        }
      },
      deps,
      controller.signal
    )
    if (disposed || confirmAbort !== controller) return
    confirmAbort = null
    shiftConfirming = false
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) {
      renderShiftCheck()
      return
    }
    if (!result.ok) {
      shiftRefs.status.textContent = ''
      showShiftCheckError(result.error)
      return
    }
    if (result.data.venueId !== venueId) {
      shiftRefs.status.textContent = ''
      showShiftCheckError({
        status: 500,
        code: ApiErrorCodes.INTERNAL_ERROR,
        message: ''
      })
      return
    }

    menu = result.data.categories
    clearShiftDraft()
    hideError()
    renderMenu()
    shiftRefs.status.textContent =
      `Проверка меню завершена. Изменено позиций: ${result.data.changedItemCount}, ` +
      `опций: ${result.data.changedOptionCount}.`
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const createCategory = async () => {
    const name = refs.createCategoryInput.value.trim()
    if (!name) {
      showToast('Введите название категории')
      return
    }
    if (!canManage) {
      showToast('Недостаточно прав')
      return
    }
    const context = captureMenuContext()
    const result = await venueCreateCategory(backendUrl, { venueId, body: { name } }, deps)
    if (disposed) return
    if (!result.ok) {
      reportMutationFailure(result.error)
      return
    }
    refs.createCategoryInput.value = ''
    refs.createCategoryForm.hidden = true
    refs.createCategoryAction.setAttribute('aria-expanded', 'false')
    const summarySelector = categorySummarySelector(result.data.id)
    await refreshAfterMutation(
      {
        ...context,
        categoryId: result.data.id,
        anchorSelector: summarySelector,
        focusSelector: summarySelector
      },
      'Категория добавлена'
    )
  }

  const disposables: Array<() => void> = []
  const recordUserInteraction = () => {
    if (disposed || programmaticRestoreDepth > 0) return
    if (activeInteractionScrollFrame != null) {
      window.cancelAnimationFrame(activeInteractionScrollFrame)
      activeInteractionScrollFrame = null
      refs.categories.style.overflowAnchor = ''
    }
    programmaticScrollPosition = null
    interactionGeneration += 1
  }
  const recordScrollInteraction = () => {
    if (disposed || programmaticRestoreDepth > 0) return
    if (activeInteractionScrollFrame != null) return
    if (
      programmaticScrollPosition &&
      Math.abs(window.scrollX - programmaticScrollPosition.x) <= 0.5 &&
      Math.abs(window.scrollY - programmaticScrollPosition.y) <= 0.5
    ) {
      programmaticScrollPosition = null
      return
    }
    recordUserInteraction()
  }
  document.addEventListener('pointerdown', recordUserInteraction, true)
  document.addEventListener('touchstart', recordUserInteraction, { capture: true, passive: true })
  document.addEventListener('focusin', recordUserInteraction, true)
  document.addEventListener('keydown', recordUserInteraction, true)
  window.addEventListener('wheel', recordUserInteraction, { capture: true, passive: true })
  window.addEventListener('scroll', recordScrollInteraction, { passive: true })
  disposables.push(() => {
    document.removeEventListener('pointerdown', recordUserInteraction, true)
    document.removeEventListener('touchstart', recordUserInteraction, true)
    document.removeEventListener('focusin', recordUserInteraction, true)
    document.removeEventListener('keydown', recordUserInteraction, true)
    window.removeEventListener('wheel', recordUserInteraction, true)
    window.removeEventListener('scroll', recordScrollInteraction)
  })
  if (canManage) {
    disposables.push(
      on(refs.createCategoryAction, 'click', () => {
        refs.createCategoryForm.hidden = !refs.createCategoryForm.hidden
        refs.createCategoryAction.setAttribute(
          'aria-expanded',
          String(!refs.createCategoryForm.hidden)
        )
        if (!refs.createCategoryForm.hidden) refs.createCategoryInput.focus()
      })
    )
    disposables.push(on(refs.createCategoryButton, 'click', () => void createCategory()))
  }
  if (refs.shiftCheck) {
    const shiftRefs = refs.shiftCheck
    const editingSummary = refs.editingDetails.querySelector<HTMLElement>(':scope > summary')
    const shiftSummary = shiftRefs.details.querySelector<HTMLElement>(':scope > summary')
    if (editingSummary) {
      disposables.push(
        on(editingSummary, 'click', () => {
          if (!refs.editingDetails.open) shiftRefs.details.open = false
        })
      )
    }
    if (shiftSummary) {
      disposables.push(
        on(shiftSummary, 'click', () => {
          if (!shiftRefs.details.open) refs.editingDetails.open = false
        })
      )
    }
    disposables.push(
      on(shiftRefs.searchInput, 'input', () => {
        shiftSearch = shiftRefs.searchInput.value
        renderShiftCheck()
      })
    )
    Object.entries(shiftRefs.filterButtons).forEach(([filter, button]) => {
      disposables.push(
        on(button, 'click', () => {
          shiftFilter = filter as ShiftCheckFilter
          renderShiftCheck()
        })
      )
    })
    disposables.push(
      on(shiftRefs.massModeButton, 'click', () => {
        shiftMassMode = true
        renderShiftCheck()
        shiftRefs.exitMassModeButton.focus()
      })
    )
    disposables.push(
      on(shiftRefs.selectFilteredButton, 'click', () => {
        const allSelected =
          filteredItemIds.length + filteredOptionIds.length > 0 &&
          filteredItemIds.every((itemId) => selectedItemIds.has(itemId)) &&
          filteredOptionIds.every((optionId) => selectedOptionIds.has(optionId))
        filteredItemIds.forEach((itemId) => {
          if (allSelected) selectedItemIds.delete(itemId)
          else selectedItemIds.add(itemId)
        })
        filteredOptionIds.forEach((optionId) => {
          if (allSelected) selectedOptionIds.delete(optionId)
          else selectedOptionIds.add(optionId)
        })
        renderShiftCheck()
      })
    )
    disposables.push(
      on(shiftRefs.clearSelectionButton, 'click', () => {
        selectedItemIds.clear()
        selectedOptionIds.clear()
        renderShiftCheck()
        shiftRefs.exitMassModeButton.focus()
      })
    )
    disposables.push(
      on(shiftRefs.exitMassModeButton, 'click', () => {
        selectedItemIds.clear()
        selectedOptionIds.clear()
        shiftMassMode = false
        renderShiftCheck()
        shiftRefs.massModeButton.focus()
      })
    )
    disposables.push(
      on(shiftRefs.selectedAvailableButton, 'click', () => {
        selectedItemIds.forEach((itemId) => {
          const item = findItemById(itemId)
          if (item) setItemDraft(item, true)
        })
        selectedOptionIds.forEach((optionId) => {
          const option = findOptionById(optionId)
          if (option) setOptionDraft(option, true)
        })
        shiftRefs.status.textContent = ''
        renderShiftCheck()
      })
    )
    disposables.push(
      on(shiftRefs.selectedUnavailableButton, 'click', () => {
        selectedItemIds.forEach((itemId) => {
          const item = findItemById(itemId)
          if (item) setItemDraft(item, false)
        })
        selectedOptionIds.forEach((optionId) => {
          const option = findOptionById(optionId)
          if (option) setOptionDraft(option, false)
        })
        shiftRefs.status.textContent = ''
        renderShiftCheck()
      })
    )
    disposables.push(
      on(shiftRefs.cancelButton, 'click', () => {
        clearShiftDraft()
        shiftRefs.status.textContent = 'Несохранённые изменения отменены.'
        hideError()
        renderShiftCheck()
      })
    )
    disposables.push(on(shiftRefs.confirmButton, 'click', () => void submitShiftCheck()))
  }

  refs.createCategoryButton.disabled = !canManage
  refs.createCategoryButton.title = canManage ? '' : 'Недостаточно прав'

  void loadMenu({ rebaseDraft: false })

  return () => {
    disposed = true
    loadSeq += 1
    interactionGeneration += 1
    restoreGeneration += 1
    programmaticScrollPosition = null
    if (activeInteractionScrollFrame != null) {
      window.cancelAnimationFrame(activeInteractionScrollFrame)
      activeInteractionScrollFrame = null
    }
    refs.categories.style.overflowAnchor = ''
    clearMenuSuccess()
    loadAbort?.abort()
    confirmAbort?.abort()
    loadAbort = null
    confirmAbort = null
    clearShiftDraft()
    disposables.forEach((dispose) => dispose())
  }
}
