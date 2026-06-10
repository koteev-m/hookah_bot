import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestAddBatch, guestCreateSharedTab, guestCreateTabInvite, guestGetTabs, guestJoinTab, guestPreviewCart } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type { CartPreviewDto, CreateTabInviteResponse, GuestTabDto } from '../shared/api/guestDtos'
import {
  addToCart,
  clearCart,
  getCartSnapshot,
  removeCartLine,
  setCartCommentDraft,
  setCartLineQty,
  subscribeCart,
  type CartLine
} from '../shared/state/cartStore'
import { getSelectedGuestTabId, setSelectedGuestTabId } from '../shared/state/guestTabSelection'
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
const MAX_TAB_TOKEN_LENGTH = 128

type CartScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigateOrder: () => void
}

type CartRefs = {
  items: HTMLDivElement
  emptyState: HTMLParagraphElement
  previewCard: HTMLDivElement
  previewContent: HTMLDivElement
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
  switchTabButton: HTMLButtonElement
  createSharedButton: HTMLButtonElement
  joinTokenInput: HTMLInputElement
  joinButton: HTMLButtonElement
  inviteSummary: HTMLParagraphElement
  inviteToken: HTMLParagraphElement
}

type TabSelectionState = {
  tabs: GuestTabDto[]
  selectedTabId: number | null
  loading: boolean
  creatingShared: boolean
  joining: boolean
}

type SharedInviteState = {
  tabId: number
  token: string
  expiresAtEpochSeconds: number
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
  const previewCard = el('div', { className: 'card cart-preview-card' })
  previewCard.hidden = true
  const previewTitle = el('h3', { text: 'Итого' })
  const previewContent = el('div', { className: 'cart-preview-content' })
  append(previewCard, previewTitle, previewContent)

  const commentCard = el('div', { className: 'card' })
  const tabLabel = el('p', { className: 'field-label', text: 'Счёт для заказа' })
  const tabSelector = document.createElement('select')
  tabSelector.className = 'staff-select'
  tabSelector.disabled = true
  tabSelector.hidden = true
  const tabSummary = el('p', { className: 'cart-summary', text: 'Текущий счёт: Личный счёт' })
  const tabMessage = el('p', { className: 'cart-message', text: '' })
  tabMessage.hidden = true
  const switchTabButton = el('button', { className: 'button-secondary', text: 'Переключить счёт' }) as HTMLButtonElement
  switchTabButton.hidden = true
  const createSharedButton = el('button', { className: 'button-secondary', text: 'Создать общий счёт' }) as HTMLButtonElement
  const joinRow = el('div', { className: 'cart-join-row' })
  const joinTokenInput = document.createElement('input')
  joinTokenInput.type = 'text'
  joinTokenInput.placeholder = 'Код приглашения'
  joinTokenInput.className = 'qty-input cart-join-input'
  joinTokenInput.hidden = true
  const joinButton = el('button', { className: 'button-secondary', text: 'Присоединиться к общему счёту' }) as HTMLButtonElement
  append(joinRow, joinTokenInput, joinButton)
  const inviteSummary = el('p', { className: 'cart-summary', text: 'Общий счёт создан' })
  const inviteToken = el('p', { className: 'cart-summary', text: '' })
  inviteSummary.hidden = true
  inviteToken.hidden = true
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
    switchTabButton,
    createSharedButton,
    joinRow,
    inviteSummary,
    inviteToken,
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
  append(wrapper, header, items, previewCard, commentCard, actionCard)
  root.replaceChildren(wrapper)

  return {
    items,
    emptyState,
    previewCard,
    previewContent,
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
    switchTabButton,
    createSharedButton,
    joinTokenInput,
    joinButton,
    inviteSummary,
    inviteToken
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

function formatCartLinePrice(line: CartLine): string | null {
  const meta = getItemMeta(line.itemId)
  if (!meta) {
    return null
  }
  const optionDelta =
    line.priceDeltaMinor ??
    (line.selectedOptionId != null
      ? meta.options?.find((option) => option.id === line.selectedOptionId)?.priceDeltaMinor
      : null) ??
    0
  return formatPrice(meta.priceMinor + optionDelta, meta.currency)
}

function formatCartLineOptionName(line: CartLine): string | null {
  if (line.selectedOptionName) {
    return line.selectedOptionName
  }
  if (line.selectedOptionId == null) {
    return null
  }
  return getItemMeta(line.itemId)?.options?.find((option) => option.id === line.selectedOptionId)?.name ?? null
}

function formatMoney(amountMinor: number, currency: string) {
  return formatPrice(amountMinor, currency || 'RUB')
}

function formatDiscount(amountMinor: number, currency: string) {
  return `−${formatMoney(amountMinor, currency)}`
}

function compareCartRequestItems(
  left: { itemId: number; selectedOptionId?: number | null },
  right: { itemId: number; selectedOptionId?: number | null }
): number {
  return left.itemId - right.itemId || (left.selectedOptionId ?? 0) - (right.selectedOptionId ?? 0)
}

function isLoyaltyDiscount(ruleType: string | null | undefined, label: string) {
  return ruleType?.toUpperCase() === 'LOYALTY_NTH_HOOKAH' || label.toLowerCase().includes('лояльность')
}

function appendPreviewRow(container: HTMLElement, label: string, value: string, isTotal = false) {
  const row = el('div', { className: isTotal ? 'order-bill-row order-bill-total' : 'order-bill-row' })
  append(row, el('span', { text: label }), el('strong', { text: value }))
  container.appendChild(row)
}

export function renderCartScreen(options: CartScreenOptions) {
  const { root, backendUrl, isDebug, onNavigateOrder } = options
  if (!root) return () => undefined

  const refs = buildCartDom(root)
  refs.commentInput.value = getCartSnapshot().commentDraft
  refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
  let disposed = false
  let isSubmitting = false
  let isChatSending = false
  let submitAbort: AbortController | null = null
  let tabActionAbort: AbortController | null = null
  let lastSubmitFingerprint: string | null = null
  let lastSubmitIdempotencyKey: string | null = null
  let cartSnapshot = getCartSnapshot()
  let tableSnapshot = getTableContext()
  const currentTelegramUserId = getTelegramContext().telegramUserId
  const tabState: TabSelectionState = {
    tabs: [],
    selectedTabId: null,
    loading: false,
    creatingShared: false,
    joining: false
  }
  let isJoinMode = false
  let hasSharedAccess = false
  let sharedInvite: SharedInviteState | null = null
  let inviteRestoreAbort: AbortController | null = null
  let inviteRestoreTabId: number | null = null
  let tabsAbort: AbortController | null = null
  let previewAbort: AbortController | null = null
  let previewTimer: number | null = null
  let previewFingerprint: string | null = null
  let previewData: CartPreviewDto | null = null
  let previewLoading = false
  let previewMessage = ''
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

  const extractJoinToken = (value: string) => {
    const trimmed = value.trim()
    if (!trimmed) {
      return ''
    }
    try {
      const parsedUrl = new URL(trimmed, window.location.origin)
      const hashQueryRaw = parsedUrl.hash.split('?')[1] ?? ''
      const hashQuery = new URLSearchParams(hashQueryRaw)
      const tokenFromUrl =
        parsedUrl.searchParams.get('tabInviteToken') ??
        parsedUrl.searchParams.get('splitToken') ??
        hashQuery.get('tabInviteToken') ??
        hashQuery.get('splitToken')
      if (tokenFromUrl) {
        return tokenFromUrl.trim()
      }
    } catch {
      return trimmed
    }
    return trimmed
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

  const setTabMessage = (text: string, tone: 'info' | 'error' | 'success' = 'info') => {
    refs.tabMessage.textContent = text
    refs.tabMessage.hidden = !text
    refs.tabMessage.dataset.tone = tone
  }

  const setSharedInvite = (invite: SharedInviteState | null) => {
    sharedInvite = invite
    refs.inviteSummary.hidden = invite == null
    refs.inviteToken.hidden = invite == null
    if (!invite) {
      refs.inviteToken.textContent = ''
      return
    }
    refs.inviteToken.textContent = `Код приглашения: ${invite.token}`
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

  const isTableReady = () =>
    tableSnapshot.status === 'resolved' && Boolean(tableSnapshot.tableToken) && tableSnapshot.orderAllowed

  const findPersonalTab = () => tabState.tabs.find((tab) => tab.type === 'PERSONAL' && tab.status === 'ACTIVE') ?? null

  const setSelectedTabId = (tabId: number | null) => {
    tabState.selectedTabId = tabId
    setSelectedGuestTabId(tableSnapshot.tableSessionId, tabId)
  }

  const getSelectedTab = () =>
    tabState.tabs.find(
      (tab) =>
        tab.id === tabState.selectedTabId &&
        tab.status === 'ACTIVE' &&
        tab.tableSessionId === tableSnapshot.tableSessionId
    ) ?? null

  const getActiveTabs = () => tabState.tabs.filter((tab) => tab.status === 'ACTIVE')

  const isOwnerSharedTab = (tab: GuestTabDto | null): tab is GuestTabDto => {
    if (!tab || tab.type !== 'SHARED' || tab.status !== 'ACTIVE') {
      return false
    }
    if (currentTelegramUserId == null) {
      return false
    }
    return tab.ownerUserId === currentTelegramUserId
  }

  const restoreSharedInviteForOwnerTab = async () => {
    const selectedTab = getSelectedTab()
    const tableSessionId = tableSnapshot.tableSessionId
    if (!selectedTab || !tableSessionId || !isOwnerSharedTab(selectedTab)) {
      return
    }
    if (sharedInvite && sharedInvite.tabId === selectedTab.id) {
      return
    }
    if (inviteRestoreAbort && inviteRestoreTabId === selectedTab.id) {
      return
    }

    inviteRestoreAbort?.abort()
    const controller = new AbortController()
    inviteRestoreAbort = controller
    inviteRestoreTabId = selectedTab.id
    const deps = buildApiDeps(isDebug)
    const result = await guestCreateTabInvite(
      backendUrl,
      selectedTab.id,
      { tableSessionId },
      deps,
      controller.signal
    )
    if (disposed) {
      return
    }
    if (controller.signal.aborted || inviteRestoreAbort !== controller) {
      if (inviteRestoreAbort === controller) {
        inviteRestoreAbort = null
        inviteRestoreTabId = null
      }
      return
    }
    inviteRestoreAbort = null
    inviteRestoreTabId = null
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      return
    }
    setSharedInvite({
      tabId: selectedTab.id,
      token: result.data.token,
      expiresAtEpochSeconds: result.data.expiresAtEpochSeconds
    })
    updateSubmitState()
  }

  const getSimpleToggleTabs = () => {
    const activeTabs = getActiveTabs()
    if (activeTabs.length !== 2) {
      return null
    }
    const personalTab = activeTabs.find((tab) => tab.type === 'PERSONAL') ?? null
    const sharedTabs = activeTabs.filter((tab) => tab.type === 'SHARED')
    if (!personalTab || sharedTabs.length !== 1) {
      return null
    }
    return { personalTab, sharedTab: sharedTabs[0] }
  }

  const formatTabTitle = (tab: GuestTabDto): string => {
    if (tab.type === 'PERSONAL') {
      return 'Личный счёт'
    }
    return `Общий счёт #${tab.id}`
  }

  const updateTabsUi = () => {
    const activeTabs = getActiveTabs()
    if (!hasSharedAccess) {
      const personal = findPersonalTab()
      if (personal) {
        tabState.selectedTabId = personal.id
      }
    }
    const selectedTab = getSelectedTab()
    const toggleTabs = hasSharedAccess ? getSimpleToggleTabs() : null
    const isCurrentSharedOwner = isOwnerSharedTab(selectedTab)
    const showCreateJoinActions = !hasSharedAccess
    if (!showCreateJoinActions && isJoinMode) {
      isJoinMode = false
      refs.joinTokenInput.value = ''
    }
    refs.tabSelector.replaceChildren()
    if (!activeTabs.length) {
      refs.tabSelector.appendChild(new Option('Сначала загрузите стол', ''))
    } else {
      activeTabs.forEach((tab) => refs.tabSelector.appendChild(new Option(formatTabTitle(tab), String(tab.id))))
    }
    refs.tabSelector.value = selectedTab ? String(selectedTab.id) : ''
    const summary =
      !hasSharedAccess || !selectedTab
        ? 'Личный счёт'
        : selectedTab.type === 'PERSONAL'
          ? 'Личный счёт'
          : 'Общий счёт'
    refs.tabSummary.textContent = `Текущий счёт: ${summary}`
    refs.tabSelector.hidden = !hasSharedAccess || toggleTabs !== null || activeTabs.length <= 1
    refs.tabSelector.disabled = tabState.loading || tabState.creatingShared || tabState.joining || !activeTabs.length
    refs.switchTabButton.hidden = !hasSharedAccess || toggleTabs === null
    if (toggleTabs) {
      const targetLabel = selectedTab?.type === 'SHARED' ? 'Личный счёт' : 'Общий счёт'
      refs.switchTabButton.textContent = `Переключить на ${targetLabel}`
    }
    refs.switchTabButton.disabled = tabState.loading || tabState.creatingShared || tabState.joining || !isTableReady()
    refs.createSharedButton.hidden = !showCreateJoinActions
    refs.createSharedButton.disabled = tabState.loading || tabState.creatingShared || tabState.joining || !isTableReady()
    refs.joinButton.hidden = !showCreateJoinActions
    refs.joinTokenInput.hidden = !showCreateJoinActions || !isJoinMode
    refs.joinTokenInput.disabled =
      tabState.loading || tabState.creatingShared || tabState.joining || !isTableReady() || !showCreateJoinActions
    refs.joinButton.textContent = isJoinMode ? 'Присоединиться по коду' : 'Присоединиться к общему счёту'
    refs.joinButton.disabled =
      tabState.loading || tabState.creatingShared || tabState.joining || !isTableReady() || !showCreateJoinActions
    if (isJoinMode && !refs.joinTokenInput.value.trim()) {
      refs.joinButton.disabled = true
    }
    const showInvite = sharedInvite != null && isCurrentSharedOwner && sharedInvite.tabId === selectedTab?.id
    refs.inviteSummary.hidden = !showInvite
    refs.inviteToken.hidden = !showInvite
    if (isCurrentSharedOwner && !showInvite) {
      void restoreSharedInviteForOwnerTab()
    }
  }

  const syncDefaultTabSelection = () => {
    const selectedTab = getSelectedTab()
    if (selectedTab) {
      setSelectedGuestTabId(tableSnapshot.tableSessionId, selectedTab.id)
      return
    }
    const storedTabId = getSelectedGuestTabId(tableSnapshot.tableSessionId)
    const storedTab =
      storedTabId != null
        ? tabState.tabs.find(
            (tab) =>
              tab.id === storedTabId && tab.status === 'ACTIVE' && tab.tableSessionId === tableSnapshot.tableSessionId
          ) ?? null
        : null
    const personal = findPersonalTab()
    setSelectedTabId(storedTab?.id ?? personal?.id ?? tabState.tabs[0]?.id ?? null)
  }

  const reloadTabs = async () => {
    if (tableSnapshot.status !== 'resolved' || !tableSnapshot.tableSessionId) {
      tabState.tabs = []
      tabState.selectedTabId = null
      updateSubmitState()
      return
    }
    const tableSessionId = tableSnapshot.tableSessionId
    tabState.loading = true
    updateSubmitState()
    tabsAbort?.abort()
    const controller = new AbortController()
    tabsAbort = controller
    const deps = buildApiDeps(isDebug)
    const result = await guestGetTabs(backendUrl, tableSessionId, deps, controller.signal)
    if (disposed || controller.signal.aborted || tabsAbort !== controller) {
      return
    }
    tabsAbort = null
    tabState.loading = false
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      setTabMessage('Не удалось загрузить счета. Попробуйте снова.', 'error')
      updateSubmitState()
      return
    }
    if (tableSnapshot.tableSessionId !== tableSessionId) {
      updateSubmitState()
      return
    }
    tabState.tabs = result.data.tabs
    hasSharedAccess = tabState.tabs.some(
      (tab) => tab.status === 'ACTIVE' && tab.type === 'SHARED' && tab.tableSessionId === tableSessionId
    )
    syncDefaultTabSelection()
    setTabMessage('')
    updateSubmitState()
    if (pendingJoinToken && !initialTabsLoadedForSession.has(tableSessionId)) {
      initialTabsLoadedForSession.add(tableSessionId)
      const token = pendingJoinToken
      pendingJoinToken = null
      void handleJoinTab(token)
      return
    }
    initialTabsLoadedForSession.add(tableSessionId)
  }

  const buildPreviewItems = () =>
    Array.from(cartSnapshot.items.values())
      .map((line) => ({
        itemId: line.itemId,
        qty: line.qty,
        ...(line.selectedOptionId != null ? { selectedOptionId: line.selectedOptionId } : {})
      }))
      .sort(compareCartRequestItems)

  const buildPreviewFingerprint = (tableToken: string, tableSessionId: number, tabId: number) =>
    JSON.stringify({
      tableToken,
      tableSessionId,
      tabId,
      items: buildPreviewItems().map((item) => [item.itemId, item.selectedOptionId ?? null, item.qty])
    })

  const resetCartPreview = (message = '') => {
    previewAbort?.abort()
    previewAbort = null
    if (previewTimer !== null) {
      window.clearTimeout(previewTimer)
      previewTimer = null
    }
    previewFingerprint = null
    previewData = null
    previewLoading = false
    previewMessage = message
    renderCartPreview()
  }

  const renderCartPreview = () => {
    const hasItems = cartSnapshot.items.size > 0
    refs.previewCard.hidden = !hasItems
    refs.previewContent.replaceChildren()
    if (!hasItems) {
      return
    }
    if (previewLoading) {
      refs.previewContent.appendChild(el('p', { className: 'cart-summary', text: 'Считаем итог…' }))
      return
    }
    if (previewData) {
      const promoDiscounts = previewData.discounts.filter((discount) => !isLoyaltyDiscount(discount.ruleType, discount.label))
      const loyaltyDiscounts = previewData.discounts.filter((discount) => isLoyaltyDiscount(discount.ruleType, discount.label))
      if (promoDiscounts.length || loyaltyDiscounts.length) {
        appendPreviewRow(refs.previewContent, 'Сумма до скидок', formatMoney(previewData.grossTotalMinor, previewData.currency))
        promoDiscounts.forEach((discount) => {
          appendPreviewRow(
            refs.previewContent,
            discount.label || 'Акция',
            formatDiscount(discount.discountMinor, discount.currency)
          )
        })
        loyaltyDiscounts.forEach((discount) => {
          appendPreviewRow(
            refs.previewContent,
            discount.label || 'Лояльность',
            formatDiscount(discount.discountMinor, discount.currency)
          )
        })
      }
      appendPreviewRow(
        refs.previewContent,
        'К оплате',
        formatMoney(previewData.finalPayableTotalMinor, previewData.currency),
        true
      )
      return
    }
    refs.previewContent.appendChild(
      el('p', {
        className: 'cart-summary',
        text: previewMessage || 'Итог будет рассчитан при отправке заказа.'
      })
    )
  }

  const loadCartPreview = async (fingerprint: string, tableToken: string, tableSessionId: number, tabId: number) => {
    previewAbort?.abort()
    const controller = new AbortController()
    previewAbort = controller
    previewLoading = true
    previewMessage = ''
    renderCartPreview()
    const deps = buildApiDeps(isDebug)
    const result = await guestPreviewCart(
      backendUrl,
      {
        tableToken,
        tableSessionId,
        tabId,
        items: buildPreviewItems()
      },
      deps,
      controller.signal
    )
    if (disposed || controller.signal.aborted || previewAbort !== controller || previewFingerprint !== fingerprint) {
      return
    }
    previewAbort = null
    previewLoading = false
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        renderCartPreview()
        return
      }
      previewData = null
      previewMessage = 'Итог будет рассчитан при отправке заказа.'
      renderCartPreview()
      return
    }
    previewData = result.data.preview
    previewMessage = ''
    renderCartPreview()
  }

  const scheduleCartPreview = () => {
    if (disposed) return
    if (!cartSnapshot.items.size) {
      resetCartPreview()
      return
    }
    const selectedTab = getSelectedTab()
    const tableToken = tableSnapshot.tableToken
    const tableSessionId = tableSnapshot.tableSessionId
    if (!isTableReady() || !tableToken || !tableSessionId || !selectedTab) {
      resetCartPreview('Итог будет рассчитан при отправке заказа.')
      return
    }
    const fingerprint = buildPreviewFingerprint(tableToken, tableSessionId, selectedTab.id)
    if (fingerprint === previewFingerprint && (previewLoading || previewData)) {
      renderCartPreview()
      return
    }
    previewFingerprint = fingerprint
    previewData = null
    previewMessage = ''
    if (previewTimer !== null) {
      window.clearTimeout(previewTimer)
    }
    previewTimer = window.setTimeout(() => {
      previewTimer = null
      void loadCartPreview(fingerprint, tableToken, tableSessionId, selectedTab.id)
    }, 250)
    renderCartPreview()
  }

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
    updateTabsUi()
    scheduleCartPreview()
  }

  const renderItems = () => {
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []
    refs.items.replaceChildren()
    if (cartSnapshot.items.size === 0) {
      refs.items.appendChild(refs.emptyState)
      return
    }
    for (const line of cartSnapshot.items.values()) {
      const row = el('div', { className: 'cart-item' })
      const info = el('div', { className: 'cart-item-info' })
      const name = el('strong', { text: formatItemTitle(line.itemId) })
      const optionName = formatCartLineOptionName(line)
      const priceText = formatCartLinePrice(line) ?? formatItemPrice(line.itemId)
      append(info, name)
      if (optionName) {
        info.appendChild(el('span', { className: 'cart-item-option', text: `Вкус: ${optionName}` }))
      }
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
      qtyInput.value = String(line.qty)
      const plusButton = el('button', { className: 'button-small', text: '+' }) as HTMLButtonElement
      plusButton.disabled = line.qty >= MAX_ITEM_QTY
      append(qtyControls, minusButton, qtyInput, plusButton)

      const removeButton = el('button', { className: 'button-small cart-remove', text: 'Удалить' }) as HTMLButtonElement
      append(controls, qtyControls, removeButton)
      append(row, info, controls)
      refs.items.appendChild(row)

      itemDisposables.push(
        on(minusButton, 'click', () => {
          setMessage('')
          removeCartLine(line.key)
        }),
        on(plusButton, 'click', () => {
          setMessage('')
          const result = addToCart(line.itemId, {
            selectedOptionId: line.selectedOptionId,
            selectedOptionName: line.selectedOptionName,
            priceDeltaMinor: line.priceDeltaMinor
          })
          if (!result.ok) {
            setMessage(result.reason === 'limit' ? 'Можно выбрать не более 50 разных позиций.' : 'Некорректное значение.')
          }
        }),
        on(removeButton, 'click', () => {
          setMessage('')
          setCartLineQty(line.key, 0)
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
            qtyInput.value = String(cartSnapshot.items.get(line.key)?.qty ?? line.qty)
            return
          }
          const result = setCartLineQty(line.key, nextValue)
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
    for (const line of cartSnapshot.items.values()) {
      if (line.qty < 1 || line.qty > MAX_ITEM_QTY) {
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
    Array.from(cartSnapshot.items.values())
      .map((line) => ({
        itemId: line.itemId,
        qty: line.qty,
        ...(line.selectedOptionId != null ? { selectedOptionId: line.selectedOptionId } : {})
      }))
      .sort(compareCartRequestItems)

  const buildSubmitFingerprint = (
    tableToken: string,
    tableSessionId: number,
    tabId: number,
    comment: string | null,
    items: Array<{ itemId: number; qty: number; selectedOptionId?: number | null }>
  ) =>
    JSON.stringify({
      tableToken,
      tableSessionId,
      tabId,
      comment,
      items: items.map((item) => [item.itemId, item.selectedOptionId ?? null, item.qty])
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
    hideSubmitError()
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
    hideSubmitError()
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
      cmd: 'start_quick_order',
      table_token: tableToken
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
    const openResult = openBotChat(telegramContext, {
      tableToken,
      tableSessionId: validation.tableSessionId
    })
    if (openResult.ok) {
      setChatMessage('Откройте чат с ботом и отправьте заказ там.')
      return
    }
    setChatMessage('Откройте чат с ботом вручную.')
  }

  const handleCreateSharedTab = async () => {
    if (tabState.loading || tabState.creatingShared || tabState.joining) return
    if (!tableSnapshot.tableSessionId) {
      setTabMessage('Сначала дождитесь загрузки стола.', 'error')
      return
    }
    const sessionId = tableSnapshot.tableSessionId
    tabActionAbort?.abort()
    const controller = new AbortController()
    tabActionAbort = controller
    tabState.creatingShared = true
    setTabMessage('')
    setSharedInvite(null)
    updateSubmitState()
    const deps = buildApiDeps(isDebug)
    const result = await guestCreateSharedTab(
      backendUrl,
      { tableSessionId: sessionId },
      deps,
      controller.signal
    )
    if (disposed) {
      return
    }
    if (controller.signal.aborted || tabActionAbort !== controller || tableSnapshot.tableSessionId !== sessionId) {
      const isCurrent = tabActionAbort === controller
      if (isCurrent) {
        tabActionAbort = null
        tabState.creatingShared = false
        updateSubmitState()
      }
      return
    }
    if (!result.ok) {
      tabState.creatingShared = false
      tabActionAbort = null
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateSubmitState()
        return
      }
      setTabMessage('Не удалось создать общий счёт.', 'error')
      updateSubmitState()
      return
    }
    setSelectedTabId(result.data.tab.id)
    hasSharedAccess = true
    const inviteResult = await guestCreateTabInvite(
      backendUrl,
      result.data.tab.id,
      { tableSessionId: sessionId },
      deps,
      controller.signal
    )
    if (disposed) {
      return
    }
    if (controller.signal.aborted || tabActionAbort !== controller || tableSnapshot.tableSessionId !== sessionId) {
      const isCurrent = tabActionAbort === controller
      if (isCurrent) {
        tabActionAbort = null
        tabState.creatingShared = false
        updateSubmitState()
      }
      return
    }
    tabState.creatingShared = false
    tabActionAbort = null
    if (!inviteResult.ok) {
      const code = normalizeErrorCode(inviteResult.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateSubmitState()
        return
      }
      setTabMessage('Общий счёт создан, но не удалось получить приглашение.', 'info')
      await reloadTabs()
      updateSubmitState()
      return
    }
    const inviteData: CreateTabInviteResponse = inviteResult.data
    setSharedInvite({
      tabId: result.data.tab.id,
      token: inviteData.token,
      expiresAtEpochSeconds: inviteData.expiresAtEpochSeconds
    })
    setTabMessage('Общий счёт создан. Приглашение готово.', 'success')
    await reloadTabs()
    updateSubmitState()
  }

  const handleJoinTab = async (tokenOverride?: string) => {
    if (tabState.loading || tabState.creatingShared || tabState.joining) return
    if (!tableSnapshot.tableSessionId) {
      setTabMessage('Сначала дождитесь загрузки стола.', 'error')
      return
    }
    const token = extractJoinToken(tokenOverride ?? refs.joinTokenInput.value)
    if (!token) {
      setTabMessage('Введите код приглашения.', 'error')
      return
    }
    if (token.length > MAX_TAB_TOKEN_LENGTH) {
      setTabMessage('Код приглашения слишком длинный.', 'error')
      return
    }
    const sessionId = tableSnapshot.tableSessionId
    tabActionAbort?.abort()
    const controller = new AbortController()
    tabActionAbort = controller
    tabState.joining = true
    setTabMessage('')
    updateSubmitState()
    const deps = buildApiDeps(isDebug)
    const result = await guestJoinTab(
      backendUrl,
      { tableSessionId: sessionId, token, consent: true },
      deps,
      controller.signal
    )
    if (disposed) {
      return
    }
    if (controller.signal.aborted || tabActionAbort !== controller || tableSnapshot.tableSessionId !== sessionId) {
      const isCurrent = tabActionAbort === controller
      if (isCurrent) {
        tabActionAbort = null
        tabState.joining = false
        updateSubmitState()
      }
      return
    }
    tabState.joining = false
    tabActionAbort = null
    if (!result.ok) {
      const code = normalizeErrorCode(result.error)
      if (code === ApiErrorCodes.UNAUTHORIZED || code === ApiErrorCodes.INITDATA_INVALID) {
        clearSession()
      }
      if (code === ApiErrorCodes.REQUEST_ABORTED) {
        updateSubmitState()
        return
      }
      setTabMessage('Не удалось присоединиться к общему счёту.', 'error')
      updateSubmitState()
      return
    }
    setSelectedTabId(result.data.tab.id)
    hasSharedAccess = true
    isJoinMode = false
    refs.joinTokenInput.value = ''
    setTabMessage('Вы присоединились к общему счёту.', 'success')
    await reloadTabs()
    updateSubmitState()
  }

  updateSubmitState()
  renderItems()

  const initialTabsLoadedForSession = new Set<number>()
  let pendingJoinToken: string | null = parseJoinTokenFromLocation() || null

  disposables.push(
    on(refs.sendButton, 'click', () => {
      void handleSubmit()
    }),
    on(refs.chatButton, 'click', () => {
      handleChatOrder()
    }),
    on(refs.tabSelector, 'change', () => {
      const parsed = Number(refs.tabSelector.value)
      setSelectedTabId(Number.isFinite(parsed) && parsed > 0 ? parsed : null)
      setTabMessage('')
      updateSubmitState()
    }),
    on(refs.switchTabButton, 'click', () => {
      if (!hasSharedAccess) {
        return
      }
      const selectedTab = getSelectedTab()
      const toggleTabs = getSimpleToggleTabs()
      if (!selectedTab || !toggleTabs) {
        return
      }
      setSelectedTabId(selectedTab.type === 'PERSONAL' ? toggleTabs.sharedTab.id : toggleTabs.personalTab.id)
      setTabMessage('')
      updateSubmitState()
    }),
    on(refs.createSharedButton, 'click', () => {
      void handleCreateSharedTab()
    }),
    on(refs.joinButton, 'click', () => {
      if (!isJoinMode) {
        isJoinMode = true
        setTabMessage('')
        updateTabsUi()
        refs.joinTokenInput.focus()
        return
      }
      void handleJoinTab()
    }),
    on(refs.joinTokenInput, 'input', () => {
      updateTabsUi()
    }),
    on(refs.commentInput, 'input', () => {
      if (refs.commentInput.value.length > MAX_COMMENT_LENGTH) {
        refs.commentInput.value = refs.commentInput.value.slice(0, MAX_COMMENT_LENGTH)
      }
      refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
      setCartCommentDraft(refs.commentInput.value)
    })
  )

  const cartSubscription = subscribeCart((snapshot) => {
    cartSnapshot = snapshot
    if (refs.commentInput.value !== snapshot.commentDraft) {
      refs.commentInput.value = snapshot.commentDraft
      refs.commentCounter.textContent = `${refs.commentInput.value.length}/${MAX_COMMENT_LENGTH}`
    }
    renderItems()
    updateSubmitState()
  })

  const tableSubscription = subscribeTable((snapshot) => {
    const previousTableSessionId = tableSnapshot.tableSessionId
    tableSnapshot = snapshot
    if (snapshot.tableSessionId !== previousTableSessionId) {
      tabActionAbort?.abort()
      tabActionAbort = null
      inviteRestoreAbort?.abort()
      inviteRestoreAbort = null
      inviteRestoreTabId = null
      tabState.tabs = []
      tabState.selectedTabId = null
      tabState.creatingShared = false
      tabState.joining = false
      isJoinMode = false
      hasSharedAccess = false
      setSharedInvite(null)
      refs.joinTokenInput.value = ''
      setTabMessage('')
      updateSubmitState()
    }
    if (snapshot.status === 'resolved' && snapshot.tableSessionId && snapshot.tableSessionId !== previousTableSessionId) {
      void reloadTabs()
    }
    if (snapshot.status !== 'resolved') {
      inviteRestoreAbort?.abort()
      inviteRestoreAbort = null
      inviteRestoreTabId = null
      tabState.tabs = []
      tabState.selectedTabId = null
      isJoinMode = false
      hasSharedAccess = false
      setSharedInvite(null)
      refs.joinTokenInput.value = ''
    }
    updateSubmitState()
  })

  if (tableSnapshot.status === 'resolved' && tableSnapshot.tableSessionId) {
    void reloadTabs()
  }

  return () => {
    disposed = true
    submitAbort?.abort()
    tabsAbort?.abort()
    tabActionAbort?.abort()
    inviteRestoreAbort?.abort()
    previewAbort?.abort()
    if (previewTimer !== null) {
      window.clearTimeout(previewTimer)
      previewTimer = null
    }
    tabActionAbort = null
    cartSubscription()
    tableSubscription()
    itemDisposables.forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
