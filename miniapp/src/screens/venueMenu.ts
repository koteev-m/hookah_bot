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

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const shiftCheck = canShiftCheck ? buildShiftCheckDom() : null

  append(wrapper, title, status, error, editingDetails, shiftCheck?.details)
  root.replaceChildren(wrapper)

  return {
    status,
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
    namePrompt: isHookah ? 'Название вкуса' : 'Название опции',
    pricePrompt: isHookah ? 'Доплата к вкусу, ₽' : 'Доплата к опции, ₽',
    deleteConfirm: isHookah ? 'Удалить вкус?' : 'Удалить опцию?',
    emptyText: 'Добавьте вкусы, чтобы гости выбирали их при заказе.',
    addedToast: isHookah ? 'Вкус добавлен' : 'Опция добавлена',
    updatedToast: isHookah ? 'Вкус обновлён' : 'Опция обновлена',
    deletedToast: isHookah ? 'Вкус удалён' : 'Опция удалена',
    enabledToast: isHookah ? 'Вкус доступен гостям' : 'Опция доступна гостям',
    disabledToast: isHookah ? 'Вкус скрыт от гостей' : 'Опция скрыта от гостей'
  }
}

function renderOptionRow(
  option: VenueMenuOptionDto,
  itemName: string,
  currency: string,
  canManage: boolean,
  canManageAvailability: boolean,
  copy: ReturnType<typeof getOptionCopy>,
  handlers: {
    onEditOption: (option: VenueMenuOptionDto, name: string, priceDeltaMinor: number) => void
    onDeleteOption: (option: VenueMenuOptionDto) => void
    onSetOptionAvailability: (option: VenueMenuOptionDto, isAvailable: boolean) => void
  }
) {
  const row = el('div', { className: 'venue-menu-option' })
  row.dataset.optionId = String(option.id)
  const info = el('div', { className: 'venue-menu-option-info' })
  const name = el('span', { text: option.name })
  append(info, name)
  const price = formatOptionPrice(option, currency)
  if (price) {
    info.appendChild(el('span', { className: 'venue-menu-item-price', text: price }))
  }
  if (!option.isAvailable) {
    info.appendChild(el('span', { className: 'menu-item-badge', text: 'Стоп-лист' }))
  }

  const actions = el('div', { className: 'venue-menu-option-actions' })

  if (canManageAvailability) {
    const availabilityLabel = el('label', { className: 'venue-menu-option-toggle' })
    const availabilityInput = document.createElement('input')
    const availabilityText = el('span', {
      text: option.isAvailable ? 'Доступен гостям' : 'В стоп-листе'
    })
    availabilityInput.type = 'checkbox'
    availabilityInput.checked = option.isAvailable
    availabilityInput.setAttribute(
      'aria-label',
      option.isAvailable
        ? `Доступен гостям: вариант ${option.name} для ${itemName}`
        : `В стоп-листе: вариант ${option.name} для ${itemName}`
    )
    availabilityInput.addEventListener('change', () => {
      availabilityInput.disabled = true
      handlers.onSetOptionAvailability(option, availabilityInput.checked)
    })
    append(availabilityLabel, availabilityInput, availabilityText)
    actions.appendChild(availabilityLabel)
  }

  if (canManage) {
    const editButton = el('button', { className: 'button-small', text: copy.editButton }) as HTMLButtonElement
    const deleteButton = el('button', { className: 'button-small button-secondary', text: copy.deleteButton }) as HTMLButtonElement
    editButton.addEventListener('click', () => {
      const nextName = window.prompt(copy.namePrompt, option.name)
      if (nextName === null) return
      const trimmed = nextName.trim()
      const priceRaw = window.prompt(copy.pricePrompt, String(option.priceDeltaMinor / 100))
      if (priceRaw === null) return
      const priceDeltaMinor = parseOptionPriceDeltaMinor(priceRaw)
      if (!trimmed || priceDeltaMinor === null) {
        showToast('Проверьте название и доплату')
        return
      }
      handlers.onEditOption(option, trimmed, priceDeltaMinor)
    })
    deleteButton.addEventListener('click', () => {
      if (!window.confirm(copy.deleteConfirm)) return
      handlers.onDeleteOption(option)
    })
    append(actions, editButton, deleteButton)
  }

  append(row, info)
  if (actions.childElementCount > 0) {
    row.appendChild(actions)
  }
  return row
}

function renderItemRow(
  item: VenueMenuItemDto,
  canManage: boolean,
  canManageAvailability: boolean,
  onEdit: (item: VenueMenuItemDto) => void,
  onDelete: (item: VenueMenuItemDto) => void,
  onSetItemAvailability: (item: VenueMenuItemDto, isAvailable: boolean) => void,
  onMove: (item: VenueMenuItemDto, direction: 'up' | 'down') => void,
  onCreateOption: (item: VenueMenuItemDto, name: string, priceDeltaMinor: number) => void,
  onApplyBaseFlavorProfiles: (item: VenueMenuItemDto) => void,
  onEditOption: (option: VenueMenuOptionDto, name: string, priceDeltaMinor: number) => void,
  onDeleteOption: (option: VenueMenuOptionDto) => void,
  onSetOptionAvailability: (option: VenueMenuOptionDto, isAvailable: boolean) => void
) {
  const itemOptions = getScopedOptions(item)
  const isHookah = isHookahMenuItem(item)
  const optionCopy = getOptionCopy(isHookah)
  const shouldRenderOptions = itemOptions.length > 0 || (isHookah && canManage)
  const canAddOptions = canManage && (isHookah || itemOptions.length > 0)
  const canApplyBaseFlavorProfiles = isHookah && canManage && (item.missingBaseFlavorProfilesCount ?? 0) > 0

  const row = el('div', { className: 'venue-menu-item' })
  row.dataset.itemId = String(item.id)
  const info = el('div', { className: 'venue-menu-item-info' })
  const name = el('strong', { text: item.name })
  const price = el('span', { className: 'venue-menu-item-price', text: formatPrice(item.priceMinor, item.currency) })
  append(info, name, price)
  if (!item.isAvailable) {
    info.appendChild(el('span', { className: 'menu-item-badge', text: 'Стоп-лист' }))
  }
  if (shouldRenderOptions) {
    const optionSection = el('div', { className: 'venue-menu-option-section' })
    const optionHeader = el('div', { className: 'venue-menu-option-header' })
    const optionsTitle = el('span', { className: 'venue-menu-options-title', text: optionCopy.title })
    optionHeader.appendChild(optionsTitle)
    if (canApplyBaseFlavorProfiles) {
      const applyBaseButton = el('button', { className: 'button-small', text: 'Добавить базовые вкусы' }) as HTMLButtonElement
      applyBaseButton.addEventListener('click', () => {
        onApplyBaseFlavorProfiles(item)
      })
      optionHeader.appendChild(applyBaseButton)
    }
    if (canAddOptions) {
      const addOptionButton = el('button', { className: 'button-small', text: optionCopy.addButton }) as HTMLButtonElement
      addOptionButton.addEventListener('click', () => {
        const nextName = window.prompt(optionCopy.namePrompt, '')
        if (nextName === null) return
        const trimmed = nextName.trim()
        const priceRaw = window.prompt(optionCopy.pricePrompt, '0')
        if (priceRaw === null) return
        const priceDeltaMinor = parseOptionPriceDeltaMinor(priceRaw)
        if (!trimmed || priceDeltaMinor === null) {
          showToast('Проверьте название и доплату')
          return
        }
        onCreateOption(item, trimmed, priceDeltaMinor)
      })
      optionHeader.appendChild(addOptionButton)
    }
    const optionsList = el('div', { className: 'venue-menu-options' })
    if (!itemOptions.length) {
      optionsList.appendChild(el('p', { className: 'venue-empty', text: optionCopy.emptyText }))
    } else {
      itemOptions.forEach((option) => {
        optionsList.appendChild(
          renderOptionRow(option, item.name, item.currency, canManage, canManageAvailability, optionCopy, {
            onEditOption,
            onDeleteOption,
            onSetOptionAvailability
          })
        )
      })
    }
    append(optionSection, optionHeader, optionsList)
    info.appendChild(optionSection)
  }

  const actions = el('div', { className: 'venue-menu-item-actions' })
  const editButton = el('button', { className: 'button-small', text: 'Править позицию' }) as HTMLButtonElement
  const deleteButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement
  const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
  const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement

  editButton.addEventListener('click', () => onEdit(item))
  deleteButton.addEventListener('click', () => onDelete(item))
  upButton.addEventListener('click', () => onMove(item, 'up'))
  downButton.addEventListener('click', () => onMove(item, 'down'))

  if (canManageAvailability) {
    const availabilityLabel = el('label', { className: 'venue-menu-option-toggle' })
    const availabilityInput = document.createElement('input')
    const availabilityText = el('span', {
      text: item.isAvailable ? 'Доступно гостям' : 'В стоп-листе'
    })
    availabilityInput.type = 'checkbox'
    availabilityInput.checked = item.isAvailable
    availabilityInput.setAttribute(
      'aria-label',
      item.isAvailable ? `Доступно гостям: ${item.name}` : `В стоп-листе: ${item.name}`
    )
    availabilityInput.addEventListener('change', () => {
      availabilityInput.disabled = true
      onSetItemAvailability(item, availabilityInput.checked)
    })
    append(availabilityLabel, availabilityInput, availabilityText)
    actions.appendChild(availabilityLabel)
  }

  if (canManage) {
    append(actions, editButton, upButton, downButton, deleteButton)
  }
  if (actions.childElementCount > 0) {
    append(row, info, actions)
  } else {
    append(row, info)
  }
  return row
}

function renderCategoryCard(
  category: VenueMenuCategoryDto,
  canManage: boolean,
  canManageAvailability: boolean,
  expanded: boolean,
  handlers: {
    onExpandedChange: (categoryId: number, expanded: boolean) => void
    onRename: (category: VenueMenuCategoryDto) => void
    onDelete: (category: VenueMenuCategoryDto) => void
    onMoveCategory: (category: VenueMenuCategoryDto, direction: 'up' | 'down') => void
    onCreateItem: (category: VenueMenuCategoryDto, name: string, priceMinor: number, currency: string) => void
    onEditItem: (item: VenueMenuItemDto) => void
    onDeleteItem: (item: VenueMenuItemDto) => void
    onSetItemAvailability: (item: VenueMenuItemDto, isAvailable: boolean) => void
    onMoveItem: (item: VenueMenuItemDto, direction: 'up' | 'down') => void
    onCreateOption: (item: VenueMenuItemDto, name: string, priceDeltaMinor: number) => void
    onApplyBaseFlavorProfiles: (item: VenueMenuItemDto) => void
    onEditOption: (option: VenueMenuOptionDto, name: string, priceDeltaMinor: number) => void
    onDeleteOption: (option: VenueMenuOptionDto) => void
    onSetOptionAvailability: (option: VenueMenuOptionDto, isAvailable: boolean) => void
  }
) {
  const card = el('details', { className: 'venue-menu-category' })
  card.dataset.categoryId = String(category.id)
  card.open = expanded
  card.addEventListener('toggle', () => handlers.onExpandedChange(category.id, card.open))
  const summary = el('summary', { className: 'venue-menu-category-summary' })
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
  const renameButton = el('button', { className: 'button-small', text: 'Переименовать' }) as HTMLButtonElement
  const deleteButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement
  const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
  const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement

  renameButton.addEventListener('click', () => handlers.onRename(category))
  deleteButton.addEventListener('click', () => handlers.onDelete(category))
  upButton.addEventListener('click', () => handlers.onMoveCategory(category, 'up'))
  downButton.addEventListener('click', () => handlers.onMoveCategory(category, 'down'))

  if (canManage) {
    append(controls, renameButton, upButton, downButton, deleteButton)
    header.appendChild(controls)
  }

  const list = el('div', { className: 'venue-menu-items' })
  if (!category.items.length) {
    list.appendChild(el('p', { className: 'venue-empty', text: 'Пусто.' }))
  }
  category.items.forEach((item) => {
    list.appendChild(
      renderItemRow(
        item,
        canManage,
        canManageAvailability,
        handlers.onEditItem,
        handlers.onDeleteItem,
        handlers.onSetItemAvailability,
          handlers.onMoveItem,
          handlers.onCreateOption,
          handlers.onApplyBaseFlavorProfiles,
          handlers.onEditOption,
          handlers.onDeleteOption,
        handlers.onSetOptionAvailability
      )
    )
  })

  const createRow = el('div', { className: 'venue-form-row' })
  const nameInput = document.createElement('input')
  nameInput.className = 'venue-input'
  nameInput.placeholder = 'Название позиции'
  const priceInput = document.createElement('input')
  priceInput.className = 'venue-input'
  priceInput.type = 'text'
  priceInput.placeholder = 'Цена (например 350)'
  const currencySelect = document.createElement('select')
  currencySelect.className = 'venue-select'
  currencySelect.appendChild(new Option(DEFAULT_CURRENCY, DEFAULT_CURRENCY))
  const createButton = el('button', { className: 'button-small', text: 'Добавить позицию' }) as HTMLButtonElement
  createButton.addEventListener('click', () => {
    const priceMinor = parsePriceMinor(priceInput.value)
    const trimmed = nameInput.value.trim()
    if (!trimmed || priceMinor === null) {
      showToast('Заполните название и цену')
      return
    }
    handlers.onCreateItem(category, trimmed, priceMinor, currencySelect.value)
    nameInput.value = ''
    priceInput.value = ''
  })
  append(createRow, nameInput, priceInput, currencySelect, createButton)

  if (canManage) {
    body.appendChild(header)
  }
  body.appendChild(list)
  if (canManage) {
    body.appendChild(createRow)
  }
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
          onRename: async (target) => {
            const nextName = window.prompt('Новое имя категории', target.name)
            if (!nextName) return
            const trimmed = nextName.trim()
            if (!trimmed) {
              showToast('Имя не может быть пустым')
              return
            }
            const result = await venueUpdateCategory(
              backendUrl,
              { venueId, categoryId: target.id, body: { name: trimmed } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Категория обновлена')
            void loadMenu()
          },
          onDelete: async (target) => {
            if (!window.confirm('Удалить категорию? Она должна быть пустой.')) return
            const result = await venueDeleteCategory(backendUrl, { venueId, categoryId: target.id }, deps)
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Категория удалена')
            void loadMenu()
          },
          onMoveCategory: async (target, direction) => {
            const idx = menu.findIndex((item) => item.id === target.id)
            if (idx < 0) return
            const nextIdx = direction === 'up' ? idx - 1 : idx + 1
            if (nextIdx < 0 || nextIdx >= menu.length) return
            const reordered = [...menu]
            const [moved] = reordered.splice(idx, 1)
            reordered.splice(nextIdx, 0, moved)
            const result = await venueReorderCategories(
              backendUrl,
              { venueId, body: { categoryIds: reordered.map((item) => item.id) } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Порядок обновлён')
            void loadMenu()
          },
          onCreateItem: async (target, name, priceMinor, currency) => {
            const result = await venueCreateItem(
              backendUrl,
              { venueId, body: { categoryId: target.id, name, priceMinor, currency, isAvailable: true } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Позиция добавлена')
            void loadMenu()
          },
          onEditItem: async (item) => {
            const nextName = window.prompt('Название позиции', item.name)
            if (nextName === null) return
            const trimmed = nextName.trim()
            const priceRaw = window.prompt('Цена (например 350)', String(item.priceMinor / 100))
            if (priceRaw === null) return
            const priceMinor = parsePriceMinor(priceRaw)
            if (!trimmed || priceMinor === null) {
              showToast('Проверьте данные')
              return
            }
            const result = await venueUpdateItem(
              backendUrl,
              { venueId, itemId: item.id, body: { name: trimmed, priceMinor, currency: item.currency } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Позиция обновлена')
            void loadMenu()
          },
          onDeleteItem: async (item) => {
            if (!window.confirm('Удалить позицию?')) return
            const result = await venueDeleteItem(backendUrl, { venueId, itemId: item.id }, deps)
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Позиция удалена')
            void loadMenu()
          },
          onSetItemAvailability: async (item, isAvailable) => {
            const result = await venueSetItemAvailability(
              backendUrl,
              { venueId, itemId: item.id, body: { isAvailable } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(isAvailable ? 'Позиция доступна гостям' : 'Позиция в стоп-листе')
            void loadMenu()
          },
          onMoveItem: async (item, direction) => {
            const category = menu.find((cat) => cat.id === item.categoryId)
            if (!category) return
            const idx = category.items.findIndex((it) => it.id === item.id)
            const nextIdx = direction === 'up' ? idx - 1 : idx + 1
            if (idx < 0 || nextIdx < 0 || nextIdx >= category.items.length) return
            const reordered = [...category.items]
            const [moved] = reordered.splice(idx, 1)
            reordered.splice(nextIdx, 0, moved)
            const result = await venueReorderItems(
              backendUrl,
              { venueId, body: { categoryId: category.id, itemIds: reordered.map((it) => it.id) } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast('Порядок позиций обновлён')
            void loadMenu()
          },
          onCreateOption: async (item, name, priceDeltaMinor) => {
            const result = await venueCreateOption(
              backendUrl,
              { venueId, body: { itemId: item.id, name, priceDeltaMinor, isAvailable: true } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(getOptionCopy(isHookahMenuItem(item)).addedToast)
            void loadMenu()
          },
          onApplyBaseFlavorProfiles: async (item) => {
            const result = await venueApplyBaseFlavorProfiles(backendUrl, { venueId, itemId: item.id }, deps)
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(`Добавлено вкусов: ${result.data.addedCount}. Уже были: ${result.data.existingCount}.`)
            void loadMenu()
          },
          onEditOption: async (option, name, priceDeltaMinor) => {
            const result = await venueUpdateOption(
              backendUrl,
              { venueId, optionId: option.id, body: { name, priceDeltaMinor } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(getOptionCopyForOption(option).updatedToast)
            void loadMenu()
          },
          onDeleteOption: async (option) => {
            const result = await venueDeleteOption(backendUrl, { venueId, optionId: option.id }, deps)
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(getOptionCopyForOption(option).deletedToast)
            void loadMenu()
          },
          onSetOptionAvailability: async (option, isAvailable) => {
            const result = await venueSetOptionAvailability(
              backendUrl,
              { venueId, optionId: option.id, body: { isAvailable } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            const copy = getOptionCopyForOption(option)
            showToast(isAvailable ? copy.enabledToast : copy.disabledToast)
            void loadMenu()
          }
          }
        )
      )
    })
    renderShiftCheck()
  }

  const loadMenu = async (
    options: { rebaseDraft?: boolean; shiftRefresh?: boolean } = {}
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
    menu = result.data.categories
    shiftNeedsRefresh = false
    renderMenu()
    if (refs.shiftCheck && options.shiftRefresh) {
      refs.shiftCheck.status.textContent =
        'Проверка обновлена. Проверьте изменения и подтвердите ещё раз.'
    }
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
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
    const result = await venueCreateCategory(backendUrl, { venueId, body: { name } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    refs.createCategoryInput.value = ''
    refs.createCategoryForm.hidden = true
    refs.createCategoryAction.setAttribute('aria-expanded', 'false')
    showToast('Категория добавлена')
    void loadMenu()
  }

  const disposables: Array<() => void> = []
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
    loadAbort?.abort()
    confirmAbort?.abort()
    loadAbort = null
    confirmAbort = null
    clearShiftDraft()
    disposables.forEach((dispose) => dispose())
  }
}
