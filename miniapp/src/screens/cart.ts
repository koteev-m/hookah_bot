import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestAddBatch } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import {
  addToCart,
  clearCart,
  getCartSnapshot,
  removeFromCart,
  setCartQty,
  subscribeCart
} from '../shared/state/cartStore'
import { getItemMeta } from '../shared/state/itemCache'
import { getTableContext, subscribe as subscribeTable } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

const MAX_ITEMS = 50
const MAX_ITEM_QTY = 50
const MAX_COMMENT_LENGTH = 500

type CartScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigateOrder: () => void
}

type CartRefs = {
  items: HTMLDivElement
  emptyState: HTMLParagraphElement
  commentInput: HTMLTextAreaElement
  sendButton: HTMLButtonElement
  message: HTMLParagraphElement
  tableHint: HTMLParagraphElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function resolveTableHint(snapshot: ReturnType<typeof getTableContext>): string | null {
  switch (snapshot.status) {
    case 'missing':
      return 'Сначала отсканируйте QR'
    case 'invalid':
      return 'Некорректный QR. Обновите и попробуйте снова.'
    case 'notFound':
      return 'Стол не найден / обновите QR'
    case 'resolving':
      return 'Загрузка стола…'
    case 'error':
      return 'Не удалось загрузить стол. Попробуйте позже.'
    case 'resolved':
      if (!snapshot.tableToken) {
        return 'Сначала отсканируйте QR'
      }
      if (!snapshot.orderAllowed) {
        return snapshot.blockReasonText ?? 'Заказы временно недоступны.'
      }
      return null
    default:
      return null
  }
}

function resolveSubmitError(error: ApiErrorInfo): string {
  const code = normalizeErrorCode(error)
  switch (code) {
    case ApiErrorCodes.INVALID_INPUT:
      return error.message && error.message.trim() ? error.message : 'Некорректные данные заказа.'
    case ApiErrorCodes.NOT_FOUND:
      return 'Стол не найден / обновите QR'
    case ApiErrorCodes.SERVICE_SUSPENDED:
      return 'Заведение временно недоступно'
    case ApiErrorCodes.SUBSCRIPTION_BLOCKED:
      return 'Заказы временно недоступны'
    case ApiErrorCodes.DATABASE_UNAVAILABLE:
      return 'База недоступна, попробуйте позже'
    case ApiErrorCodes.NETWORK_ERROR:
      return 'Нет соединения'
    case ApiErrorCodes.UNAUTHORIZED:
    case ApiErrorCodes.INITDATA_INVALID:
      return 'Сессия истекла — перезапустите Mini App'
    default:
      return 'Не удалось отправить заказ. Попробуйте позже.'
  }
}

function buildCartDom(root: HTMLDivElement): CartRefs {
  const wrapper = el('div', { className: 'cart-screen' })
  const header = el('div', { className: 'card' })
  const title = el('h3', { text: 'Корзина' })
  const tableHint = el('p', { className: 'cart-hint', text: '' })
  tableHint.hidden = true
  append(header, title, tableHint)

  const items = el('div', { className: 'cart-items' })
  const emptyState = el('p', { className: 'cart-empty', text: 'Корзина пуста.' })

  const commentCard = el('div', { className: 'card' })
  const commentLabel = el('p', { className: 'field-label', text: 'Комментарий' })
  const commentInput = document.createElement('textarea')
  commentInput.className = 'cart-comment'
  commentInput.maxLength = MAX_COMMENT_LENGTH
  commentInput.rows = 3
  commentInput.placeholder = 'Комментарий к заказу'
  append(commentCard, commentLabel, commentInput)

  const actionCard = el('div', { className: 'card cart-actions' })
  const message = el('p', { className: 'cart-message', text: '' })
  message.hidden = true
  const sendButton = el('button', { text: 'Отправить' }) as HTMLButtonElement
  append(actionCard, message, sendButton)

  append(wrapper, header, items, commentCard, actionCard)
  root.replaceChildren(wrapper)

  return { items, emptyState, commentInput, sendButton, message, tableHint }
}

function formatItemTitle(itemId: number): string {
  const meta = getItemMeta(itemId)
  if (!meta) {
    return String(itemId)
  }
  return meta.name
}

function formatItemPrice(itemId: number): string | null {
  const meta = getItemMeta(itemId)
  if (!meta) {
    return null
  }
  return formatPrice(meta.priceMinor, meta.currency)
}

export function renderCartScreen(options: CartScreenOptions) {
  const { root, backendUrl, isDebug, onNavigateOrder } = options
  if (!root) return () => undefined

  const refs = buildCartDom(root)
  let disposed = false
  let isSubmitting = false
  let submitAbort: AbortController | null = null
  let cartSnapshot = getCartSnapshot()
  let tableSnapshot = getTableContext()
  let itemDisposables: Array<() => void> = []
  const disposables: Array<() => void> = []

  const setMessage = (text: string) => {
    refs.message.textContent = text
    refs.message.hidden = !text
  }

  const isTableReady = () =>
    tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && tableSnapshot.orderAllowed

  const updateSubmitState = () => {
    const hasItems = cartSnapshot.items.size > 0
    const tableReady = isTableReady()
    const tableHint = tableReady ? null : resolveTableHint(tableSnapshot)
    refs.tableHint.textContent = tableHint ?? ''
    refs.tableHint.hidden = !tableHint
    refs.sendButton.disabled = isSubmitting || !hasItems || !tableReady
  }

  const renderItems = () => {
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []
    refs.items.replaceChildren()
    if (cartSnapshot.items.size === 0) {
      refs.items.appendChild(refs.emptyState)
      return
    }
    for (const [itemId, qty] of cartSnapshot.items.entries()) {
      const row = el('div', { className: 'cart-item' })
      const info = el('div', { className: 'cart-item-info' })
      const name = el('strong', { text: formatItemTitle(itemId) })
      const priceText = formatItemPrice(itemId)
      append(info, name)
      if (priceText) {
        const price = el('span', { className: 'cart-item-price', text: priceText })
        info.appendChild(price)
      }

      const controls = el('div', { className: 'cart-item-controls' })
      const qtyControls = el('div', { className: 'qty-controls' })
      const minusButton = el('button', { className: 'button-small', text: '−' }) as HTMLButtonElement
      const qtyInput = document.createElement('input')
      qtyInput.className = 'qty-input'
      qtyInput.type = 'number'
      qtyInput.min = '1'
      qtyInput.max = String(MAX_ITEM_QTY)
      qtyInput.value = String(qty)
      const plusButton = el('button', { className: 'button-small', text: '+' }) as HTMLButtonElement
      plusButton.disabled = qty >= MAX_ITEM_QTY
      append(qtyControls, minusButton, qtyInput, plusButton)

      const removeButton = el('button', { className: 'button-small cart-remove', text: 'Удалить' }) as HTMLButtonElement
      append(controls, qtyControls, removeButton)
      append(row, info, controls)
      refs.items.appendChild(row)

      itemDisposables.push(
        on(minusButton, 'click', () => {
          setMessage('')
          removeFromCart(itemId)
        }),
        on(plusButton, 'click', () => {
          setMessage('')
          const result = addToCart(itemId)
          if (!result.ok) {
            setMessage(result.reason === 'limit' ? 'Можно выбрать не более 50 разных позиций.' : 'Некорректное значение.')
          }
        }),
        on(removeButton, 'click', () => {
          setMessage('')
          setCartQty(itemId, 0)
        }),
        on(qtyInput, 'change', () => {
          setMessage('')
          const nextValue = Number(qtyInput.value)
          if (
            !Number.isFinite(nextValue) ||
            !Number.isInteger(nextValue) ||
            nextValue < 1 ||
            nextValue > MAX_ITEM_QTY
          ) {
            qtyInput.value = String(cartSnapshot.items.get(itemId) ?? qty)
            return
          }
          const result = setCartQty(itemId, nextValue)
          if (!result.ok) {
            setMessage(result.reason === 'limit' ? 'Можно выбрать не более 50 разных позиций.' : 'Некорректное значение.')
          }
        })
      )
    }
  }

  const validateBeforeSubmit = (): { ok: true; comment: string | null } | { ok: false; reason: string } => {
    if (cartSnapshot.items.size === 0 || cartSnapshot.items.size > MAX_ITEMS) {
      return { ok: false, reason: 'Выберите от 1 до 50 позиций.' }
    }
    for (const [, qty] of cartSnapshot.items.entries()) {
      if (qty < 1 || qty > MAX_ITEM_QTY) {
        return { ok: false, reason: 'Количество каждой позиции должно быть от 1 до 50.' }
      }
    }
    const commentValue = refs.commentInput.value.trim()
    if (commentValue.length > MAX_COMMENT_LENGTH) {
      return { ok: false, reason: 'Комментарий должен быть не длиннее 500 символов.' }
    }
    if (!isTableReady()) {
      return {
        ok: false,
        reason: resolveTableHint(tableSnapshot) ?? 'Не удалось загрузить стол. Попробуйте позже.'
      }
    }
    return { ok: true, comment: commentValue ? commentValue : null }
  }

  const handleSubmit = async () => {
    if (isSubmitting) return
    setMessage('')
    const validation = validateBeforeSubmit()
    if (!validation.ok) {
      setMessage(validation.reason)
      return
    }
    const tableToken = tableSnapshot.tableToken
    if (!tableToken) {
      setMessage(resolveTableHint(tableSnapshot) ?? 'Не удалось загрузить стол. Попробуйте позже.')
      return
    }
    isSubmitting = true
    updateSubmitState()
    if (submitAbort) {
      submitAbort.abort()
    }
    const controller = new AbortController()
    submitAbort = controller
    const deps = buildApiDeps(isDebug)
    const payload = {
      tableToken,
      comment: validation.comment,
      items: Array.from(cartSnapshot.items.entries()).map(([itemId, qty]) => ({
        itemId,
        qty
      }))
    }
    const result = await guestAddBatch(backendUrl, payload, deps, controller.signal)
    if (disposed) {
      return
    }
    if (controller.signal.aborted || submitAbort !== controller) {
      if (submitAbort === controller) {
        submitAbort = null
        isSubmitting = false
        updateSubmitState()
      }
      return
    }
    isSubmitting = false
    submitAbort = null
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateSubmitState()
        return
      }
      setMessage(resolveSubmitError(result.error))
      updateSubmitState()
      return
    }
    clearCart()
    refs.commentInput.value = ''
    updateSubmitState()
    onNavigateOrder()
  }

  updateSubmitState()
  renderItems()

  disposables.push(
    on(refs.sendButton, 'click', () => {
      void handleSubmit()
    }),
    on(refs.commentInput, 'input', () => {
      if (refs.commentInput.value.length > MAX_COMMENT_LENGTH) {
        refs.commentInput.value = refs.commentInput.value.slice(0, MAX_COMMENT_LENGTH)
      }
    })
  )

  const cartSubscription = subscribeCart((snapshot) => {
    cartSnapshot = snapshot
    renderItems()
    updateSubmitState()
  })

  const tableSubscription = subscribeTable((snapshot) => {
    tableSnapshot = snapshot
    updateSubmitState()
  })

  return () => {
    disposed = true
    submitAbort?.abort()
    cartSubscription()
    tableSubscription()
    itemDisposables.forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
