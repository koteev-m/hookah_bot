import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateCategory,
  venueCreateItem,
  venueDeleteCategory,
  venueDeleteItem,
  venueGetMenu,
  venueReorderCategories,
  venueReorderItems,
  venueSetItemAvailability,
  venueUpdateCategory,
  venueUpdateItem
} from '../shared/api/venueApi'
import type { VenueAccessDto, VenueMenuCategoryDto, VenueMenuItemDto } from '../shared/api/venueDtos'
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
  categories: HTMLDivElement
  createCategoryInput: HTMLInputElement
  createCategoryButton: HTMLButtonElement
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

function buildMenuDom(root: HTMLDivElement): MenuRefs {
  const wrapper = el('div', { className: 'venue-menu-builder' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Меню' })
  const createRow = el('div', { className: 'venue-form-row' })
  const createCategoryInput = document.createElement('input')
  createCategoryInput.className = 'venue-input'
  createCategoryInput.placeholder = 'Новая категория'
  const createCategoryButton = el('button', { text: 'Добавить' }) as HTMLButtonElement
  append(createRow, createCategoryInput, createCategoryButton)
  append(header, title, createRow)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const categories = el('div', { className: 'venue-menu-categories' })

  append(wrapper, header, status, error, categories)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    categories,
    createCategoryInput,
    createCategoryButton
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

function renderItemRow(
  item: VenueMenuItemDto,
  canManage: boolean,
  onEdit: (item: VenueMenuItemDto) => void,
  onDelete: (item: VenueMenuItemDto) => void,
  onToggle: (item: VenueMenuItemDto) => void,
  onMove: (item: VenueMenuItemDto, direction: 'up' | 'down') => void
) {
  const row = el('div', { className: 'venue-menu-item' })
  const info = el('div', { className: 'venue-menu-item-info' })
  const name = el('strong', { text: item.name })
  const price = el('span', { className: 'venue-menu-item-price', text: formatPrice(item.priceMinor, item.currency) })
  append(info, name, price)
  if (!item.isAvailable) {
    info.appendChild(el('span', { className: 'menu-item-badge', text: 'Стоп-лист' }))
  }

  const actions = el('div', { className: 'venue-menu-item-actions' })
  const editButton = el('button', { className: 'button-small', text: 'Править' }) as HTMLButtonElement
  const toggleButton = el('button', { className: 'button-small', text: item.isAvailable ? 'Стоп' : 'Включить' }) as HTMLButtonElement
  const deleteButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement
  const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
  const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement

  const disableManage = !canManage
  ;[editButton, toggleButton, deleteButton, upButton, downButton].forEach((button) => {
    button.disabled = disableManage
    button.title = disableManage ? 'Недостаточно прав' : ''
  })

  editButton.addEventListener('click', () => onEdit(item))
  toggleButton.addEventListener('click', () => onToggle(item))
  deleteButton.addEventListener('click', () => onDelete(item))
  upButton.addEventListener('click', () => onMove(item, 'up'))
  downButton.addEventListener('click', () => onMove(item, 'down'))

  append(actions, editButton, toggleButton, upButton, downButton, deleteButton)
  append(row, info, actions)
  return row
}

function renderCategoryCard(
  category: VenueMenuCategoryDto,
  canManage: boolean,
  handlers: {
    onRename: (category: VenueMenuCategoryDto) => void
    onDelete: (category: VenueMenuCategoryDto) => void
    onMoveCategory: (category: VenueMenuCategoryDto, direction: 'up' | 'down') => void
    onCreateItem: (category: VenueMenuCategoryDto, name: string, priceMinor: number, currency: string) => void
    onEditItem: (item: VenueMenuItemDto) => void
    onDeleteItem: (item: VenueMenuItemDto) => void
    onToggleItem: (item: VenueMenuItemDto) => void
    onMoveItem: (item: VenueMenuItemDto, direction: 'up' | 'down') => void
  }
) {
  const card = el('div', { className: 'card venue-menu-category' })
  const header = el('div', { className: 'card-header' })
  const title = el('h3', { text: category.name })
  const controls = el('div', { className: 'venue-inline-actions' })
  const renameButton = el('button', { className: 'button-small', text: 'Переименовать' }) as HTMLButtonElement
  const deleteButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement
  const upButton = el('button', { className: 'button-small button-secondary', text: '↑' }) as HTMLButtonElement
  const downButton = el('button', { className: 'button-small button-secondary', text: '↓' }) as HTMLButtonElement

  ;[renameButton, deleteButton, upButton, downButton].forEach((button) => {
    button.disabled = !canManage
    button.title = canManage ? '' : 'Недостаточно прав'
  })

  renameButton.addEventListener('click', () => handlers.onRename(category))
  deleteButton.addEventListener('click', () => handlers.onDelete(category))
  upButton.addEventListener('click', () => handlers.onMoveCategory(category, 'up'))
  downButton.addEventListener('click', () => handlers.onMoveCategory(category, 'down'))

  append(controls, renameButton, upButton, downButton, deleteButton)
  append(header, title, controls)

  const list = el('div', { className: 'venue-menu-items' })
  if (!category.items.length) {
    list.appendChild(el('p', { className: 'venue-empty', text: 'Пусто.' }))
  }
  category.items.forEach((item) => {
    list.appendChild(
      renderItemRow(item, canManage, handlers.onEditItem, handlers.onDeleteItem, handlers.onToggleItem, handlers.onMoveItem)
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
  createButton.disabled = !canManage
  createButton.title = canManage ? '' : 'Недостаточно прав'
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

  const optionsHint = el('p', { className: 'venue-help', text: 'Опции: MVP позже' })

  append(card, header, list, createRow, optionsHint)
  return card
}

export function renderVenueMenuScreen(options: VenueMenuOptions) {
  const { root, backendUrl, isDebug, venueId, access } = options
  if (!root) return () => undefined
  const refs = buildMenuDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let loadSeq = 0
  let menu: VenueMenuCategoryDto[] = []

  const canView = access.permissions.includes('MENU_VIEW')
  const canManage = access.permissions.includes('MENU_MANAGE')

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

  const renderMenu = () => {
    refs.categories.replaceChildren()
    if (!menu.length) {
      refs.categories.appendChild(el('p', { className: 'venue-empty', text: 'Категории не найдены.' }))
      return
    }
    menu.forEach((category) => {
      refs.categories.appendChild(
        renderCategoryCard(category, canManage, {
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
          onToggleItem: async (item) => {
            const result = await venueSetItemAvailability(
              backendUrl,
              { venueId, itemId: item.id, body: { isAvailable: !item.isAvailable } },
              deps
            )
            if (disposed) return
            if (!result.ok) {
              showError(result.error)
              return
            }
            showToast(item.isAvailable ? 'В стоп-листе' : 'Возвращено в продажу')
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
          }
        })
      )
    })
  }

  const loadMenu = async () => {
    if (!canView) {
      refs.categories.replaceChildren(el('p', { className: 'venue-empty', text: 'Недостаточно прав для просмотра меню.' }))
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
    const result = await venueGetMenu(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    menu = result.data.categories
    renderMenu()
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
    showToast('Категория добавлена')
    void loadMenu()
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.createCategoryButton, 'click', () => void createCategory()))

  refs.createCategoryButton.disabled = !canManage
  refs.createCategoryButton.title = canManage ? '' : 'Недостаточно прав'

  void loadMenu()

  return () => {
    disposed = true
    loadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
