import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestAddBatch, guestCreateSharedTab, guestGetTabs, guestJoinTab, guestStaffCall } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { GuestTabDto } from '../shared/api/guestDtos'
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
import { getTelegramContext } from '../shared/telegram'
import { openBotChat, sendChatOrder } from '../shared/telegramActions'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { formatPrice } from '../shared/ui/price'
import { showToast } from '../shared/ui/toast'

const MAX_ITEMS = 50
const MAX_ITEM_QTY = 50
const MAX_COMMENT_LENGTH = 500
const MAX_STAFF_COMMENT_LENGTH = 500

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
  commentCounter: HTMLParagraphElement
  sendButton: HTMLButtonElement
  message: HTMLParagraphElement
  submitError: HTMLDivElement
  submitErrorTitle: HTMLHeadingElement
  submitErrorMessage: HTMLParagraphElement
  submitErrorActions: HTMLDivElement
  submitErrorDetails: HTMLDivElement
  disabledReason: HTMLParagraphElement
  chatButton: HTMLButtonElement
  chatMessage: HTMLParagraphElement
  tableHint: HTMLParagraphElement
  tabSelector: HTMLSelectElement
  tabSummary: HTMLParagraphElement
  tabMessage: HTMLParagraphElement
  createSharedButton: HTMLButtonElement
  joinTokenInput: HTMLInputElement
  joinButton: HTMLButtonElement
  staffReason: HTMLSelectElement
  staffComment: HTMLTextAreaElement
  staffCounter: HTMLParagraphElement
  staffButton: HTMLButtonElement
  staffMessage: HTMLParagraphElement
  staffError: HTMLDivElement
  staffErrorTitle: HTMLHeadingElement
  staffErrorMessage: HTMLParagraphElement
  staffErrorActions: HTMLDivElement
  staffErrorDetails: HTMLDivElement
  staffDisabledReason: HTMLParagraphElement
}

type TabSelectionState = {
  tabs: GuestTabDto[]
  selectedTabId: number | null
  loading: boolean
  creatingShared: boolean
  joining: boolean
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
  const tabLabel = el('p', { className: 'field-label', text: 'Счёт для заказа' })
  const tabSelector = document.createElement('select')
  tabSelector.className = 'staff-select'
  tabSelector.disabled = true
  const tabSummary = el('p', { className: 'cart-hint', text: 'Вы оформляете на: Personal' })
  const tabMessage = el('p', { className: 'cart-message', text: '' })
  tabMessage.hidden = true
  const createSharedButton = el('button', { className: 'button-secondary', text: 'Создать общий счёт' }) as HTMLButtonElement
  const joinRow = el('div', { className: 'cart-join-row' })
  const joinTokenInput = document.createElement('input')
  joinTokenInput.type = 'text'
  joinTokenInput.placeholder = 'Код/ссылка приглашения'
  joinTokenInput.className = 'qty-input cart-join-input'
  const joinButton = el('button', { className: 'button-secondary', text: 'Присоединиться по коду/ссылке' }) as HTMLButtonElement
  append(joinRow, joinTokenInput, joinButton)
  const commentLabel = el('p', { className: 'field-label', text: 'Комментарий' })
  const commentInput = document.createElement('textarea')
  commentInput.className = 'cart-comment'
  commentInput.maxLength = MAX_COMMENT_LENGTH
  commentInput.rows = 3
  commentInput.placeholder = 'Комментарий к заказу'
  const commentCounter = el('p', { className: 'field-counter', text: `0/${MAX_COMMENT_LENGTH}` })
  append(
    commentCard,
    tabLabel,
    tabSelector,
    tabSummary,
    tabMessage,
    createSharedButton,
    joinRow,
    commentLabel,
    commentInput,
    commentCounter
  )

  const actionCard = el('div', { className: 'card cart-actions' })
  const message = el('p', { className: 'cart-message', text: '' })
  message.hidden = true
  const submitError = el('div', { className: 'error-card' })
  submitError.hidden = true
  const submitErrorTitle = el('h3')
  const submitErrorMessage = el('p')
  const submitErrorActions = el('div', { className: 'error-actions' })
  const submitErrorDetails = el('div')
  append(submitError, submitErrorTitle, submitErrorMessage, submitErrorActions, submitErrorDetails)
  const sendButton = el('button', { text: 'Отправить' }) as HTMLButtonElement
  const disabledReason = el('p', { className: 'disabled-reason', text: '' })
  disabledReason.hidden = true
  const chatMessage = el('p', { className: 'cart-chat-message', text: '' })
  chatMessage.hidden = true
  const chatButton = el('button', { className: 'button-secondary', text: 'Оформить в чате' }) as HTMLButtonElement
  append(actionCard, message, submitError, sendButton, disabledReason, chatMessage, chatButton)

  const staffCard = el('div', { className: 'card staff-call' })
  const staffTitle = el('p', { className: 'field-label', text: 'Вызвать персонал' })
  const staffReasonLabel = el('p', { className: 'field-label', text: 'Причина' })
  const staffReason = document.createElement('select')
  staffReason.className = 'staff-select'
  staffReason.appendChild(new Option('Замена углей', 'COALS'))
  staffReason.appendChild(new Option('Счёт', 'BILL'))
  staffReason.appendChild(new Option('Подойти к столу', 'COME'))
  staffReason.appendChild(new Option('Другое', 'OTHER'))
  const staffCommentLabel = el('p', { className: 'field-label', text: 'Комментарий (необязательно)' })
  const staffComment = document.createElement('textarea')
  staffComment.className = 'staff-comment'
  staffComment.maxLength = MAX_STAFF_COMMENT_LENGTH
  staffComment.rows = 2
  staffComment.placeholder = 'Комментарий для персонала'
  const staffCounter = el('p', { className: 'field-counter', text: `0/${MAX_STAFF_COMMENT_LENGTH}` })
  const staffMessage = el('p', { className: 'staff-message', text: '' })
  staffMessage.hidden = true
  const staffButton = el('button', { text: 'Вызвать персонал' }) as HTMLButtonElement
  const staffError = el('div', { className: 'error-card' })
  staffError.hidden = true
  const staffErrorTitle = el('h3')
  const staffErrorMessage = el('p')
  const staffErrorActions = el('div', { className: 'error-actions' })
  const staffErrorDetails = el('div')
  append(staffError, staffErrorTitle, staffErrorMessage, staffErrorActions, staffErrorDetails)
  const staffDisabledReason = el('p', { className: 'disabled-reason', text: '' })
  staffDisabledReason.hidden = true
  append(
    staffCard,
    staffTitle,
    staffReasonLabel,
    staffReason,
    staffCommentLabel,
    staffComment,
    staffCounter,
    staffMessage,
    staffError,
    staffButton,
    staffDisabledReason
  )

  append(wrapper, header, items, commentCard, actionCard, staffCard)
  root.replaceChildren(wrapper)

  return {
    items,
    emptyState,
    commentInput,
    commentCounter,
    sendButton,
    message,
    submitError,
    submitErrorTitle,
    submitErrorMessage,
    submitErrorActions,
    submitErrorDetails,
    disabledReason,
    chatButton,
    chatMessage,
    tableHint,
    tabSelector,
    tabSummary,
    tabMessage,
    createSharedButton,
    joinTokenInput,
    joinButton,
    staffReason,
    staffComment,
    staffCounter,
    staffButton,
    staffMessage,
    staffError,
    staffErrorTitle,
    staffErrorMessage,
    staffErrorActions,
    staffErrorDetails,
    staffDisabledReason
  }
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
  refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
  refs.staffCounter.textContent = `${refs.staffComment.value.length}/${MAX_STAFF_COMMENT_LENGTH}`
  let disposed = false
  let isSubmitting = false
  let isStaffCalling = false
  let isChatSending = false
  let submitAbort: AbortController | null = null
  let staffAbort: AbortController | null = null
  let lastSubmitFingerprint: string | null = null
  let lastSubmitIdempotencyKey: string | null = null
  let cartSnapshot = getCartSnapshot()
  let tableSnapshot = getTableContext()
  const tabState: TabSelectionState = {
    tabs: [],
    selectedTabId: null,
    loading: false,
    creatingShared: false,
    joining: false
  }
  let tabsAbort: AbortController | null = null
  let itemDisposables: Array<() => void> = []
  const disposables: Array<() => void> = []

  const parseJoinTokenFromLocation = () => {
    const search = new URLSearchParams(window.location.search)
    const hashQueryRaw = window.location.hash.split('?')[1] ?? ''
    const hashQuery = new URLSearchParams(hashQueryRaw)
    return (
      search.get('tabInviteToken') ??
      search.get('splitToken') ??
      hashQuery.get('tabInviteToken') ??
      hashQuery.get('splitToken') ??
      ''
    ).trim()
  }

  const setMessage = (text: string) => {
    refs.message.textContent = text
    refs.message.hidden = !text
  }

  const setChatMessage = (text: string, tone: 'info' | 'error' | 'success' = 'info') => {
    refs.chatMessage.textContent = text
    refs.chatMessage.hidden = !text
    refs.chatMessage.dataset.tone = tone
  }

  const setStaffMessage = (text: string, tone: 'default' | 'success' = 'default') => {
    refs.staffMessage.textContent = text
    refs.staffMessage.hidden = !text
    refs.staffMessage.dataset.tone = tone
  }

  const setTabMessage = (text: string, tone: 'info' | 'error' | 'success' = 'info') => {
    refs.tabMessage.textContent = text
    refs.tabMessage.hidden = !text
    refs.tabMessage.dataset.tone = tone
  }

  const hideSubmitError = () => {
    refs.submitError.hidden = true
    refs.submitErrorActions.replaceChildren()
    refs.submitErrorDetails.replaceChildren()
  }

  const showSubmitError = (error: ApiErrorInfo) => {
    const presentation = presentApiError(error, { isDebug, scope: 'table' })
    refs.submitErrorTitle.textContent = presentation.title
    refs.submitErrorMessage.textContent = presentation.message
    refs.submitError.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void handleSubmit() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void handleSubmit() })
    }
    renderErrorActions(refs.submitErrorActions, actions)
    renderErrorDetails(refs.submitErrorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.submitError.hidden = false
  }

  const hideStaffError = () => {
    refs.staffError.hidden = true
    refs.staffErrorActions.replaceChildren()
    refs.staffErrorDetails.replaceChildren()
  }

  const showStaffError = (error: ApiErrorInfo) => {
    const presentation = presentApiError(error, { isDebug, scope: 'table' })
    refs.staffErrorTitle.textContent = presentation.title
    refs.staffErrorMessage.textContent = presentation.message
    refs.staffError.dataset.severity = presentation.severity
    const actions = presentation.actions.map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void handleStaffCall() }
      }
      return action
    })
    if (!actions.length) {
      actions.push({ label: 'Повторить', onClick: () => void handleStaffCall() })
    }
    renderErrorActions(refs.staffErrorActions, actions)
    renderErrorDetails(refs.staffErrorDetails, error, {
      isDebug,
      extraNotes: presentation.debugLine ? [presentation.debugLine] : undefined
    })
    refs.staffError.hidden = false
  }

  const isTableReady = () =>
    tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && tableSnapshot.orderAllowed

  const findPersonalTab = () => tabState.tabs.find((tab) => tab.type === 'PERSONAL' && tab.status === 'ACTIVE') ?? null

  const getSelectedTab = () =>
    tabState.tabs.find((tab) => tab.id === tabState.selectedTabId && tab.status === 'ACTIVE') ?? null

  const formatTabTitle = (tab: GuestTabDto): string => {
    if (tab.type === 'PERSONAL') {
      return `Personal · #${tab.id}`
    }
    const owner = tab.ownerUserId ? `владелец ${tab.ownerUserId}` : 'без владельца'
    return `Shared · ${owner}`
  }

  const updateTabsUi = () => {
    const selectedTab = getSelectedTab()
    refs.tabSelector.replaceChildren()
    if (!tabState.tabs.length) {
      refs.tabSelector.appendChild(new Option('Сначала загрузите стол', ''))
    } else {
      tabState.tabs
        .filter((tab) => tab.status === 'ACTIVE')
        .forEach((tab) => refs.tabSelector.appendChild(new Option(formatTabTitle(tab), String(tab.id))))
    }
    refs.tabSelector.value = selectedTab ? String(selectedTab.id) : ''
    const summary = selectedTab ? formatTabTitle(selectedTab) : 'Personal'
    refs.tabSummary.textContent = `Вы оформляете на: ${summary}`
    refs.tabSelector.disabled = tabState.loading || tabState.creatingShared || tabState.joining || !tabState.tabs.length
    refs.createSharedButton.disabled = tabState.loading || tabState.creatingShared || tabState.joining || !isTableReady()
    refs.joinButton.disabled =
      tabState.loading ||
      tabState.creatingShared ||
      tabState.joining ||
      !isTableReady() ||
      !refs.joinTokenInput.value.trim()
  }

  const syncDefaultTabSelection = () => {
    const selectedTab = getSelectedTab()
    if (selectedTab) {
      return
    }
    const personal = findPersonalTab()
    tabState.selectedTabId = personal?.id ?? tabState.tabs[0]?.id ?? null
  }

  const reloadTabs = async () => {
    if (tableSnapshot.status !== 'resolved' || !tableSnapshot.tableSessionId) {
      tabState.tabs = []
      tabState.selectedTabId = null
      updateTabsUi()
      return
    }
    tabState.loading = true
    updateTabsUi()
    tabsAbort?.abort()
    const controller = new AbortController()
    tabsAbort = controller
    const deps = buildApiDeps(isDebug)
    const result = await guestGetTabs(backendUrl, tableSnapshot.tableSessionId, deps, controller.signal)
    if (disposed || controller.signal.aborted || tabsAbort !== controller) {
      return
    }
    tabsAbort = null
    tabState.loading = false
    if (!result.ok) {
      setTabMessage('Не удалось загрузить счета. Попробуйте снова.', 'error')
      updateTabsUi()
      return
    }
    tabState.tabs = result.data.tabs
    syncDefaultTabSelection()
    setTabMessage('')
    updateTabsUi()
  }

  const canCallStaff = () => tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken)

  const updateSubmitState = () => {
    const hasItems = cartSnapshot.items.size > 0
    const tableReady = isTableReady()
    const selectedTab = getSelectedTab()
    const tableHint = tableReady ? null : resolveTableHint(tableSnapshot)
    refs.tableHint.textContent = tableHint ?? ''
    refs.tableHint.hidden = !tableHint
    const submitDisabledReason = !hasItems
      ? 'Добавьте позиции в корзину.'
      : tableHint ?? (!selectedTab ? 'Выберите счёт (tab) для заказа.' : null)
    refs.disabledReason.textContent = submitDisabledReason ?? ''
    refs.disabledReason.hidden = !submitDisabledReason
    refs.sendButton.disabled = isSubmitting || isChatSending || !hasItems || !tableReady || !selectedTab
    refs.chatButton.disabled = isSubmitting || isChatSending || !hasItems || !tableReady
    const canStaff = canCallStaff()
    const staffDisabledReason = canStaff ? null : resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR'
    refs.staffDisabledReason.textContent = staffDisabledReason ?? ''
    refs.staffDisabledReason.hidden = !staffDisabledReason
    refs.staffButton.disabled = isSubmitting || isStaffCalling || !canStaff
    updateTabsUi()
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

  const validateBeforeSubmit =
    (): | { ok: true; comment: string | null; tabId: number; tableSessionId: number }
      | { ok: false; reason: string } => {
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
    const tableSessionId = tableSnapshot.tableSessionId
    if (!tableSessionId) {
      return { ok: false, reason: 'Не удалось определить сессию стола.' }
    }
    const selectedTab = getSelectedTab()
    if (!selectedTab) {
      return { ok: false, reason: 'Выберите счёт (tab) для заказа.' }
    }
    return { ok: true, comment: commentValue ? commentValue : null, tabId: selectedTab.id, tableSessionId }
  }

  const buildSubmitItems = () =>
    Array.from(cartSnapshot.items.entries())
      .map(([itemId, qty]) => ({ itemId, qty }))
      .sort((left, right) => left.itemId - right.itemId)

  const buildSubmitFingerprint = (
    tableToken: string,
    tableSessionId: number,
    tabId: number,
    comment: string | null,
    items: Array<{ itemId: number; qty: number }>
  ) =>
    JSON.stringify({
      tableToken,
      tableSessionId,
      tabId,
      comment,
      items: items.map((item) => [item.itemId, item.qty])
    })

  const generateIdempotencyKey = () =>
    globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2)}`

  const resolveSubmitIdempotencyKey = (fingerprint: string) => {
    if (fingerprint === lastSubmitFingerprint && lastSubmitIdempotencyKey) {
      return lastSubmitIdempotencyKey
    }
    const nextIdempotencyKey = generateIdempotencyKey()
    lastSubmitFingerprint = fingerprint
    lastSubmitIdempotencyKey = nextIdempotencyKey
    return nextIdempotencyKey
  }

  const resetSubmitIdempotency = () => {
    lastSubmitFingerprint = null
    lastSubmitIdempotencyKey = null
  }

  const handleSubmit = async () => {
    if (isSubmitting) return
    setMessage('')
    setChatMessage('')
    setStaffMessage('', 'default')
    hideSubmitError()
    hideStaffError()
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
    const items = buildSubmitItems()
    const fingerprint = buildSubmitFingerprint(
      tableToken,
      validation.tableSessionId,
      validation.tabId,
      validation.comment,
      items
    )
    const payload = {
      tableToken,
      tableSessionId: validation.tableSessionId,
      tabId: validation.tabId,
      idempotencyKey: resolveSubmitIdempotencyKey(fingerprint),
      comment: validation.comment,
      items
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
      showSubmitError(result.error)
      updateSubmitState()
      return
    }
    resetSubmitIdempotency()
    clearCart()
    refs.commentInput.value = ''
    refs.commentCounter.textContent = `0/${MAX_COMMENT_LENGTH}`
    updateSubmitState()
    showToast('Отправлено в заказ')
    onNavigateOrder()
  }

  const handleChatOrder = () => {
    if (isSubmitting || isChatSending) return
    setMessage('')
    setChatMessage('')
    setStaffMessage('', 'default')
    hideSubmitError()
    hideStaffError()
    const validation = validateBeforeSubmit()
    if (!validation.ok) {
      setChatMessage(validation.reason, 'error')
      return
    }
    const tableToken = tableSnapshot.tableToken
    if (!tableToken) {
      setChatMessage(resolveTableHint(tableSnapshot) ?? 'Не удалось загрузить стол. Попробуйте позже.', 'error')
      return
    }
    const payload = {
      type: 'CHAT_ORDER',
      tableToken,
      items: Array.from(cartSnapshot.items.entries()).map(([itemId, qty]) => ({
        itemId,
        qty
      })),
      comment: validation.comment
    }
    isChatSending = true
    updateSubmitState()
    const telegramContext = getTelegramContext()
    const result = sendChatOrder(telegramContext, payload)
    isChatSending = false
    updateSubmitState()
    if (result.ok) {
      showToast('Отправлено в чат')
      setChatMessage('', 'success')
      return
    }
    const openResult = openBotChat(telegramContext)
    if (openResult.ok) {
      setChatMessage('Откройте чат с ботом и отправьте заказ там.')
      return
    }
    setChatMessage('Откройте чат с ботом вручную.')
  }

  const handleStaffCall = async () => {
    if (isSubmitting || isStaffCalling) return
    setMessage('')
    setChatMessage('')
    setStaffMessage('', 'default')
    hideSubmitError()
    hideStaffError()
    if (!canCallStaff()) {
      setStaffMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
      return
    }
    const tableToken = tableSnapshot.tableToken
    if (!tableToken) {
      setStaffMessage(resolveTableHint(tableSnapshot) ?? 'Сначала отсканируйте QR')
      return
    }
    const commentValue = refs.staffComment.value.trim()
    if (commentValue.length > MAX_STAFF_COMMENT_LENGTH) {
      setStaffMessage('Комментарий должен быть не длиннее 500 символов.')
      return
    }
    const payload = {
      tableToken,
      reason: refs.staffReason.value,
      comment: commentValue ? commentValue : null
    }
    isStaffCalling = true
    updateSubmitState()
    if (staffAbort) {
      staffAbort.abort()
    }
    const controller = new AbortController()
    staffAbort = controller
    const deps = buildApiDeps(isDebug)
    const result = await guestStaffCall(backendUrl, payload, deps, controller.signal)
    if (disposed) {
      return
    }
    if (controller.signal.aborted || staffAbort !== controller) {
      if (staffAbort === controller) {
        staffAbort = null
        isStaffCalling = false
        updateSubmitState()
      }
      return
    }
    isStaffCalling = false
    staffAbort = null
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateSubmitState()
        return
      }
      showStaffError(result.error)
      updateSubmitState()
      return
    }
    const timeLabel = new Date(result.data.createdAtEpochSeconds * 1000).toLocaleTimeString('ru-RU', {
      hour: '2-digit',
      minute: '2-digit'
    })
    setStaffMessage(`Персонал вызван (${timeLabel})`, 'success')
    refs.staffComment.value = ''
    refs.staffCounter.textContent = `0/${MAX_STAFF_COMMENT_LENGTH}`
    updateSubmitState()
    showToast('Вызов персонала отправлен')
  }

  const handleCreateSharedTab = async () => {
    if (tabState.loading || tabState.creatingShared || tabState.joining) return
    if (!tableSnapshot.tableSessionId) {
      setTabMessage('Сначала дождитесь загрузки стола.', 'error')
      return
    }
    tabState.creatingShared = true
    setTabMessage('')
    updateSubmitState()
    const deps = buildApiDeps(isDebug)
    const result = await guestCreateSharedTab(
      backendUrl,
      { tableSessionId: tableSnapshot.tableSessionId },
      deps
    )
    tabState.creatingShared = false
    if (!result.ok) {
      setTabMessage('Не удалось создать общий счёт.', 'error')
      updateSubmitState()
      return
    }
    tabState.selectedTabId = result.data.tab.id
    setTabMessage('Общий счёт создан и выбран.', 'success')
    await reloadTabs()
    updateSubmitState()
  }

  const handleJoinTab = async (tokenOverride?: string) => {
    if (tabState.loading || tabState.creatingShared || tabState.joining) return
    if (!tableSnapshot.tableSessionId) {
      setTabMessage('Сначала дождитесь загрузки стола.', 'error')
      return
    }
    const token = (tokenOverride ?? refs.joinTokenInput.value).trim()
    if (!token) {
      setTabMessage('Введите код/ссылку приглашения.', 'error')
      return
    }
    tabState.joining = true
    setTabMessage('')
    updateSubmitState()
    const deps = buildApiDeps(isDebug)
    const result = await guestJoinTab(
      backendUrl,
      { tableSessionId: tableSnapshot.tableSessionId, token, consent: true },
      deps
    )
    tabState.joining = false
    if (!result.ok) {
      setTabMessage('Не удалось присоединиться к общему счёту.', 'error')
      updateSubmitState()
      return
    }
    tabState.selectedTabId = result.data.tab.id
    refs.joinTokenInput.value = ''
    setTabMessage('Вы присоединились к общему счёту.', 'success')
    await reloadTabs()
    updateSubmitState()
  }

  updateSubmitState()
  renderItems()

  disposables.push(
    on(refs.sendButton, 'click', () => {
      void handleSubmit()
    }),
    on(refs.chatButton, 'click', () => {
      handleChatOrder()
    }),
    on(refs.tabSelector, 'change', () => {
      const parsed = Number(refs.tabSelector.value)
      tabState.selectedTabId = Number.isFinite(parsed) && parsed > 0 ? parsed : null
      setTabMessage('')
      updateSubmitState()
    }),
    on(refs.createSharedButton, 'click', () => {
      void handleCreateSharedTab()
    }),
    on(refs.joinButton, 'click', () => {
      void handleJoinTab()
    }),
    on(refs.joinTokenInput, 'input', () => {
      updateTabsUi()
    }),
    on(refs.staffButton, 'click', () => {
      void handleStaffCall()
    }),
    on(refs.commentInput, 'input', () => {
      if (refs.commentInput.value.length > MAX_COMMENT_LENGTH) {
        refs.commentInput.value = refs.commentInput.value.slice(0, MAX_COMMENT_LENGTH)
      }
      refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
    }),
    on(refs.staffComment, 'input', () => {
      if (refs.staffComment.value.length > MAX_STAFF_COMMENT_LENGTH) {
        refs.staffComment.value = refs.staffComment.value.slice(0, MAX_STAFF_COMMENT_LENGTH)
      }
      refs.staffCounter.textContent = `${refs.staffComment.value.length}/${MAX_STAFF_COMMENT_LENGTH}`
    })
  )

  const cartSubscription = subscribeCart((snapshot) => {
    cartSnapshot = snapshot
    renderItems()
    updateSubmitState()
  })

  const tableSubscription = subscribeTable((snapshot) => {
    const previousTableSessionId = tableSnapshot.tableSessionId
    tableSnapshot = snapshot
    if (snapshot.status === 'resolved' && snapshot.tableSessionId && snapshot.tableSessionId !== previousTableSessionId) {
      void reloadTabs()
    }
    if (snapshot.status !== 'resolved') {
      tabState.tabs = []
      tabState.selectedTabId = null
    }
    updateSubmitState()
  })

  const initialJoinToken = parseJoinTokenFromLocation()
  if (tableSnapshot.status === 'resolved' && tableSnapshot.tableSessionId) {
    void reloadTabs().then(() => {
      if (initialJoinToken) {
        void handleJoinTab(initialJoinToken)
      }
    })
  }

  return () => {
    disposed = true
    submitAbort?.abort()
    staffAbort?.abort()
    tabsAbort?.abort()
    cartSubscription()
    tableSubscription()
    itemDisposables.forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
