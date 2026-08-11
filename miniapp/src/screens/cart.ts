import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import { guestAddBatch, guestCreateSharedTab, guestCreateTabInvite, guestGetTabs, guestJoinTab, guestPreviewCart } from '../shared/api/guestApi'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import type {
  CartMenuSelectionIssue,
  CartPreviewDto,
  CreateTabInviteResponse,
  GiftDecisionAction,
  GiftDecisionDto,
  GiftOfferDto,
  GiftRewardItemDto,
  GuestTabDto
} from '../shared/api/guestDtos'
import {
  addToCart,
  clearCartMenuSelectionIssues,
  clearMismatchedCartGiftDecisionOwner,
  clearExpiredCartGiftDecision,
  clearCart,
  getCartSnapshot,
  reconcileCartGiftDecisionScope,
  removeCartLine,
  setCartMenuSelectionIssues,
  setCartCommentDraft,
  setCartGiftDecision,
  setCartLineQty,
  subscribeCart,
  type CartGiftDecisionContext,
  type CartGiftDecisionScope,
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
import { formatGuestTabLabel } from '../shared/utils/guestTabLabels'

const MAX_ITEMS = 50
const MAX_ITEM_QTY = 50
const MAX_COMMENT_LENGTH = 500
const MAX_TAB_TOKEN_LENGTH = 128
const GIFT_DECISION_STALE_MESSAGE = 'Корзина изменилась. Проверьте подарок ещё раз.'
const CART_CHANGED_DURING_SUBMIT_MESSAGE =
  'Заказ отправлен. Изменения, внесённые во время отправки, сохранены в корзине. ' +
  'Проверьте их и нажмите «Отправить» для нового заказа.'

type CartScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  onNavigateOrder: () => void
  onNavigateMenu: () => void
  onNavigateOptionReplacement: (cartLineRef: string) => void
  initialFocusLineRef?: string | null
}

type CartRefs = {
  heading: HTMLHeadingElement
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
  title.tabIndex = -1
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
    heading: title,
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
    return 'Позиция'
  }
  return meta.name
}

function safeItemName(itemId: number): string | null {
  return getItemMeta(itemId)?.name?.trim() || null
}

function parseCartMenuSelectionIssues(
  error: ApiErrorInfo,
  snapshot: ReturnType<typeof getCartSnapshot>
): CartMenuSelectionIssue[] | null {
  if (error.status !== 409 || error.code !== ApiErrorCodes.CART_MENU_SELECTION_UNAVAILABLE) {
    return null
  }
  const details = error.details
  if (!details || typeof details !== 'object' || !Array.isArray((details as { issues?: unknown }).issues)) {
    return null
  }
  const rawIssues = (details as { issues: unknown[] }).issues
  if (!rawIssues.length) {
    return null
  }
  const seenRefs = new Set<string>()
  const issues: CartMenuSelectionIssue[] = []
  for (const rawIssue of rawIssues) {
    if (!rawIssue || typeof rawIssue !== 'object') {
      return null
    }
    const value = rawIssue as Partial<CartMenuSelectionIssue>
    const cartLineRef = typeof value.cartLineRef === 'string' ? value.cartLineRef : ''
    const itemId = Number(value.itemId)
    const optionId = value.optionId == null ? null : Number(value.optionId)
    if (
      !cartLineRef ||
      seenRefs.has(cartLineRef) ||
      !Number.isSafeInteger(itemId) ||
      itemId <= 0 ||
      (optionId != null && (!Number.isSafeInteger(optionId) || optionId <= 0)) ||
      !['ITEM', 'OPTION'].includes(value.selectionKind ?? '') ||
      !['REMOVED', 'UNAVAILABLE'].includes(value.reason ?? '')
    ) {
      return null
    }
    const line = snapshot.items.get(cartLineRef)
    if (
      !line ||
      line.itemId !== itemId ||
      (value.selectionKind === 'OPTION' && (optionId == null || line.selectedOptionId !== optionId)) ||
      (value.selectionKind === 'ITEM' && optionId != null && line.selectedOptionId !== optionId)
    ) {
      return null
    }
    seenRefs.add(cartLineRef)
    issues.push({
      cartLineRef,
      itemId,
      optionId,
      selectionKind: value.selectionKind as CartMenuSelectionIssue['selectionKind'],
      reason: value.reason as CartMenuSelectionIssue['reason']
    })
  }
  return issues
}

function cartMenuSelectionIssueCopy(issue: CartMenuSelectionIssue, line: CartLine): string {
  const itemName = safeItemName(line.itemId)
  const optionName = formatCartLineOptionName(line)?.trim() || null
  if (issue.selectionKind === 'ITEM') {
    if (issue.reason === 'REMOVED') {
      return itemName
        ? `Позиции «${itemName}» больше нет в меню. Удалите её из корзины, чтобы продолжить заказ.`
        : 'Одной из позиций больше нет в меню. Удалите её из корзины, чтобы продолжить заказ.'
    }
    return itemName
      ? `Позиция «${itemName}» временно недоступна. Чтобы продолжить заказ, удалите её из корзины и выберите другую позицию.`
      : 'Одна из позиций временно недоступна. Чтобы продолжить заказ, удалите её из корзины и выберите другую позицию.'
  }
  if (issue.reason === 'REMOVED') {
    return itemName && optionName
      ? `Выбранного варианта «${optionName}» для позиции «${itemName}» больше нет в меню. Выберите другой вариант или удалите позицию из корзины.`
      : 'Выбранного варианта больше нет в меню. Выберите другой вариант или удалите позицию из корзины.'
  }
  return itemName && optionName
    ? `Выбранный вариант «${optionName}» для позиции «${itemName}» временно недоступен. Выберите другой вариант или удалите позицию из корзины.`
    : 'Выбранный вариант временно недоступен. Выберите другой вариант или удалите позицию из корзины.'
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

function formatPromotionLabel(label: string | null | undefined) {
  const value = label?.trim()
  if (!value) return 'Акция'
  if (/^акция(?:\s|$|«)/i.test(value)) return value
  return `Акция «${value}»`
}

function giftOfferKey(preview: CartPreviewDto, offer: GiftOfferDto | null | undefined): string {
  if (!offer || offer.status === 'NO_GIFT') return 'NO_GIFT'
  return [
    offer.promotionId ?? 'none',
    offer.ruleId ?? 'none',
    offer.ruleVersion ?? 'none',
    preview.cartFingerprint
  ].join(':')
}

function giftDecisionKey(decision: GiftDecisionDto | null | undefined): Array<string | number | null> | null {
  if (!decision) return null
  return [decision.action, decision.selectedMenuItemId ?? null, decision.decisionScopeToken]
}

function giftDecisionResolvedByOffer(decision: GiftDecisionDto, offer: GiftOfferDto | null | undefined): boolean {
  if (!offer) return false
  if (decision.action === 'SKIP') return offer.status === 'GIFT_SKIPPED'
  if (offer.status !== 'GIFT_SELECTED') return false
  if (decision.action === 'ACCEPT_FIXED') {
    return offer.selectedRewardItem?.menuItemId === offer.fixedRewardItem?.menuItemId
  }
  return offer.selectedRewardItem?.menuItemId === decision.selectedMenuItemId
}

function giftDecisionForPreview(
  preview: CartPreviewDto,
  action: GiftDecisionAction,
  selectedMenuItemId?: number
): GiftDecisionDto | null {
  const decisionScopeToken = preview.decisionScopeToken?.trim()
  const expiresAtEpochSeconds = preview.decisionScopeExpiresAtEpochSeconds
  if (
    !decisionScopeToken ||
    expiresAtEpochSeconds == null ||
    !Number.isSafeInteger(expiresAtEpochSeconds) ||
    expiresAtEpochSeconds <= Math.floor(Date.now() / 1000)
  ) {
    return null
  }
  if (
    action === 'SELECT_ITEM' &&
    (selectedMenuItemId == null || !Number.isInteger(selectedMenuItemId) || selectedMenuItemId <= 0)
  ) {
    return null
  }
  return {
    action,
    decisionScopeToken,
    ...(action === 'SELECT_ITEM' ? { selectedMenuItemId } : {})
  }
}

function giftUnavailableCopy(_reason: string | null | undefined): string {
  return 'Подарок по акции сейчас недоступен.'
}

function giftDecisionRequired(preview: CartPreviewDto | null): boolean {
  return (
    preview?.giftOffer?.status === 'FIXED_GIFT_AVAILABLE' ||
    preview?.giftOffer?.status === 'GIFT_CHOICE_REQUIRED'
  )
}

function compareCartRequestItems(
  left: { itemId: number; selectedOptionId?: number | null; preferenceNote?: string | null },
  right: { itemId: number; selectedOptionId?: number | null; preferenceNote?: string | null }
): number {
  const leftNote = left.preferenceNote?.trim() ?? ''
  const rightNote = right.preferenceNote?.trim() ?? ''
  return (
    left.itemId - right.itemId ||
    (left.selectedOptionId ?? 0) - (right.selectedOptionId ?? 0) ||
    (leftNote < rightNote ? -1 : leftNote > rightNote ? 1 : 0)
  )
}

type SubmitBusinessLine = {
  itemId: number
  qty: number
  selectedOptionId?: number | null
  preferenceNote?: string | null
}

function canonicalSubmitLines(items: Iterable<SubmitBusinessLine>) {
  return Array.from(items)
    .map((item) => ({
      itemId: item.itemId,
      selectedOptionId: item.selectedOptionId ?? null,
      preferenceNote: item.preferenceNote?.trim() || null,
      qty: item.qty
    }))
    .sort(compareCartRequestItems)
    .map((item) => [
      item.itemId,
      item.selectedOptionId ?? 'base',
      item.preferenceNote,
      item.qty
    ])
}

function cartBusinessMutationSignature(snapshot: ReturnType<typeof getCartSnapshot>) {
  return JSON.stringify({
    comment: snapshot.commentDraft.trim() || null,
    items: canonicalSubmitLines(snapshot.items.values())
  })
}

function buildCanonicalSubmitFingerprint(
  accountId: number | null,
  venueId: number | null,
  tableSessionId: number,
  tabId: number,
  comment: string | null,
  items: Iterable<SubmitBusinessLine>
) {
  return JSON.stringify({
    accountId,
    venueId,
    tableSessionId,
    tabId,
    comment: comment?.trim() || null,
    items: canonicalSubmitLines(items)
  })
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
  const { root, backendUrl, isDebug, onNavigateOrder, onNavigateMenu, onNavigateOptionReplacement } = options
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
  let cartMutationSignature = cartBusinessMutationSignature(cartSnapshot)
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
  let previewFailed = false
  let previewMessage = ''
  let pendingGiftOfferKey: string | null = null
  let pendingGiftSelectionId: number | null = null
  let itemDisposables: Array<() => void> = []
  let giftDisposables: Array<() => void> = []
  let pendingFocusLineRef = options.initialFocusLineRef ?? null
  let focusCartHeading = false
  let pendingItemMenuRecovery: {
    venueId: number | null
    tableSessionId: number | null
    tableToken: string | null
    tabId: number | null
    cartMutationSignature: string
    previewFingerprint: string | null
  } | null = null
  const disposables: Array<() => void> = []

  const focusGuestMenuHeading = () => {
    let remainingFrames = 3
    const focusHeading = () => {
      const heading = document.querySelector<HTMLElement>('.venue-screen .venue-header h3')
      if (heading) {
        heading.tabIndex = -1
        heading.focus()
        return
      }
      remainingFrames -= 1
      if (remainingFrames > 0) {
        window.requestAnimationFrame(focusHeading)
      }
    }
    window.requestAnimationFrame(focusHeading)
  }

  const finishItemMenuRecovery = (completedPreviewFingerprint: string | null) => {
    const pending = pendingItemMenuRecovery
    if (!pending) {
      return
    }
    if (
      pending.venueId !== tableSnapshot.venueId ||
      pending.tableSessionId !== tableSnapshot.tableSessionId ||
      pending.tableToken !== tableSnapshot.tableToken ||
      pending.tabId !== (getSelectedTab()?.id ?? null) ||
      pending.cartMutationSignature !== cartMutationSignature ||
      pending.previewFingerprint !== completedPreviewFingerprint
    ) {
      pendingItemMenuRecovery = null
      return
    }
    pendingItemMenuRecovery = null
    onNavigateMenu()
    focusGuestMenuHeading()
  }

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

  const applyMenuSelectionError = (error: ApiErrorInfo): boolean => {
    const issues = parseCartMenuSelectionIssues(error, cartSnapshot)
    if (!issues) {
      return false
    }
    previewData = null
    previewFailed = true
    previewMessage = 'Некоторые позиции в корзине нужно обновить.'
    setCartMenuSelectionIssues(issues)
    hideSubmitError()
    return true
  }

  const showSubmitError = (error: ApiErrorInfo, actionOverrides?: ApiErrorAction[]) => {
    const presentation = presentApiError(error, { isDebug, scope: 'table' })
    refs.submitErrorTitle.textContent = presentation.title
    refs.submitErrorMessage.textContent = presentation.message
    refs.submitError.dataset.severity = presentation.severity
    const actions = (actionOverrides ?? presentation.actions).map((action) => {
      if (action.label === 'Повторить') {
        return { ...action, onClick: () => void handleSubmit() }
      }
      return action
    })
    if (!actions.length && actionOverrides == null) {
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
    const previousTabId = tabState.selectedTabId
    tabState.selectedTabId = tabId
    setSelectedGuestTabId(tableSnapshot.tableSessionId, tabId)
    if (previousTabId !== tabId) {
      pendingItemMenuRecovery = null
      resetSubmitIdempotency()
    }
    if (previousTabId != null && previousTabId !== tabId) {
      setCartGiftDecision(null)
      return
    }
    if (
      previousTabId == null &&
      tabId != null &&
      currentTelegramUserId != null &&
      tableSnapshot.venueId != null &&
      tableSnapshot.tableSessionId != null
    ) {
      clearMismatchedCartGiftDecisionOwner({
        userId: currentTelegramUserId,
        venueId: tableSnapshot.venueId,
        tableSessionId: tableSnapshot.tableSessionId,
        tabId
      })
    }
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
      activeTabs.forEach((tab) =>
        refs.tabSelector.appendChild(new Option(formatGuestTabLabel(tab, activeTabs), String(tab.id)))
      )
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
        cartLineRef: line.key,
        itemId: line.itemId,
        qty: line.qty,
        ...(line.selectedOptionId != null ? { selectedOptionId: line.selectedOptionId } : {}),
        ...(line.preferenceNote ? { preferenceNote: line.preferenceNote } : {})
      }))
      .sort(compareCartRequestItems)

  const buildPreviewFingerprint = (tableToken: string, tableSessionId: number, tabId: number) =>
    JSON.stringify({
      tableToken,
      tableSessionId,
      tabId,
      comment: cartSnapshot.commentDraft.trim() || null,
      items: buildPreviewItems().map((item) => [
        item.cartLineRef,
        item.itemId,
        item.selectedOptionId ?? null,
        item.preferenceNote ?? null,
        item.qty
      ]),
      giftDecision: giftDecisionKey(cartSnapshot.giftDecision)
    })

  const getGiftDecisionContext = (preview: CartPreviewDto): CartGiftDecisionContext | null => {
    const venueId = tableSnapshot.venueId
    const tableSessionId = tableSnapshot.tableSessionId
    const tabId = getSelectedTab()?.id
    const cartFingerprint = preview.cartFingerprint?.trim()
    if (
      currentTelegramUserId == null ||
      currentTelegramUserId <= 0 ||
      venueId == null ||
      venueId <= 0 ||
      tableSessionId == null ||
      tableSessionId <= 0 ||
      tabId == null ||
      tabId <= 0 ||
      !cartFingerprint
    ) {
      return null
    }
    return {
      userId: currentTelegramUserId,
      venueId,
      tableSessionId,
      tabId,
      cartFingerprint
    }
  }

  const getGiftDecisionScope = (preview: CartPreviewDto): CartGiftDecisionScope | null => {
    const context = getGiftDecisionContext(preview)
    const expiresAtEpochSeconds = preview.decisionScopeExpiresAtEpochSeconds
    if (
      !context ||
      expiresAtEpochSeconds == null ||
      !Number.isSafeInteger(expiresAtEpochSeconds) ||
      expiresAtEpochSeconds <= Math.floor(Date.now() / 1000)
    ) {
      return null
    }
    return { ...context, expiresAtEpochSeconds }
  }

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
    previewFailed = false
    previewMessage = message
    pendingGiftOfferKey = null
    pendingGiftSelectionId = null
    renderCartPreview()
  }

  const appendGiftReward = (container: HTMLElement, item: GiftRewardItemDto, prefix: string) => {
    const summary = el('div', { className: 'cart-gift-reward' })
    append(
      summary,
      el('strong', { text: `${prefix}: ${item.name}` }),
      el('span', {
        className: 'cart-gift-price',
        text: `Обычная цена: ${formatMoney(item.originalUnitPriceMinor, item.currency)}`
      })
    )
    container.appendChild(summary)
  }

  const applyGiftDecision = (decision: GiftDecisionDto | null) => {
    if (previewLoading || isSubmitting) return
    pendingGiftSelectionId = null
    if (!decision) {
      setCartGiftDecision(null)
      return
    }
    const scope = previewData ? getGiftDecisionScope(previewData) : null
    if (!scope) {
      setCartGiftDecision(null)
      setMessage(GIFT_DECISION_STALE_MESSAGE)
      return
    }
    setCartGiftDecision(decision, scope)
  }

  const renderGiftOffer = (container: HTMLElement, preview: CartPreviewDto) => {
    const offer = preview.giftOffer
    if (!offer || offer.status === 'NO_GIFT') {
      pendingGiftOfferKey = null
      pendingGiftSelectionId = null
      return
    }
    const currentOfferKey = giftOfferKey(preview, offer)
    if (pendingGiftOfferKey !== currentOfferKey) {
      pendingGiftOfferKey = currentOfferKey
      pendingGiftSelectionId = null
    }
    const card = el('section', { className: 'cart-gift-offer' })
    card.dataset.status = offer.status
    card.appendChild(el('h4', { text: offer.promotionTitle?.trim() || 'Подарок при покупке' }))
    if (offer.triggerItemName?.trim()) {
      card.appendChild(
        el('p', {
          className: 'cart-gift-trigger',
          text: `Условие выполнено: ${offer.triggerItemName.trim()}`
        })
      )
    }
    const actions = el('div', { className: 'cart-gift-actions' })
    const skipButton = () => {
      const button = el('button', {
        className: 'button-secondary button-small',
        text: 'Пропустить подарок'
      }) as HTMLButtonElement
      button.disabled = previewLoading || isSubmitting
      const decision = giftDecisionForPreview(preview, 'SKIP')
      if (!decision) {
        button.disabled = true
      } else {
        giftDisposables.push(on(button, 'click', () => applyGiftDecision(decision)))
      }
      return button
    }

    if (offer.status === 'GIFT_UNAVAILABLE') {
      card.appendChild(
        el('p', {
          className: 'cart-gift-unavailable',
          text: giftUnavailableCopy(offer.unavailableReason)
        })
      )
      container.appendChild(card)
      return
    }

    if (offer.status === 'GIFT_SKIPPED') {
      card.appendChild(el('p', { className: 'cart-gift-state', text: 'Вы пропустили подарок.' }))
      const restoreButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Вернуться к подарку'
      }) as HTMLButtonElement
      restoreButton.disabled = previewLoading || isSubmitting
      giftDisposables.push(on(restoreButton, 'click', () => applyGiftDecision(null)))
      actions.appendChild(restoreButton)
      card.appendChild(actions)
      container.appendChild(card)
      return
    }

    if (offer.status === 'GIFT_SELECTED') {
      const selectedReward = offer.selectedRewardItem ?? offer.fixedRewardItem
      if (selectedReward) {
        appendGiftReward(card, selectedReward, 'Подарок добавлен')
      } else {
        card.appendChild(el('p', { className: 'cart-gift-state', text: 'Подарок добавлен.' }))
      }
      actions.appendChild(skipButton())
      card.appendChild(actions)
      container.appendChild(card)
      return
    }

    if (offer.status === 'FIXED_GIFT_AVAILABLE') {
      const fixedReward = offer.fixedRewardItem
      if (!fixedReward) {
        card.appendChild(
          el('p', {
            className: 'cart-gift-unavailable',
            text: giftUnavailableCopy(offer.unavailableReason)
          })
        )
        container.appendChild(card)
        return
      }
      appendGiftReward(card, fixedReward, 'Вам доступен подарок')
      const acceptButton = el('button', {
        className: 'button-small',
        text: 'Добавить подарок'
      }) as HTMLButtonElement
      const decision = giftDecisionForPreview(preview, 'ACCEPT_FIXED')
      acceptButton.disabled = previewLoading || isSubmitting || !decision
      if (decision) {
        giftDisposables.push(on(acceptButton, 'click', () => applyGiftDecision(decision)))
      }
      append(actions, acceptButton, skipButton())
      card.appendChild(actions)
      container.appendChild(card)
      return
    }

    const rewardItems = offer.selectableRewardItems ?? []
    if (!rewardItems.length) {
      card.appendChild(
        el('p', {
          className: 'cart-gift-unavailable',
          text: giftUnavailableCopy(offer.unavailableReason)
        })
      )
      container.appendChild(card)
      return
    }
    card.appendChild(el('p', { className: 'cart-gift-state', text: 'Выберите один подарок:' }))
    const choices = el('div', { className: 'cart-gift-choices' })
    rewardItems.forEach((item) => {
      const selected = pendingGiftSelectionId === item.menuItemId
      const button = el('button', {
        className: selected ? 'cart-gift-choice is-selected' : 'cart-gift-choice'
      }) as HTMLButtonElement
      button.type = 'button'
      button.setAttribute('aria-pressed', selected ? 'true' : 'false')
      button.disabled = previewLoading || isSubmitting
      append(
        button,
        el('strong', { text: item.name }),
        el('span', { text: formatMoney(item.originalUnitPriceMinor, item.currency) })
      )
      giftDisposables.push(
        on(button, 'click', () => {
          pendingGiftSelectionId = item.menuItemId
          renderCartPreview()
        })
      )
      choices.appendChild(button)
    })
    const confirmButton = el('button', {
      className: 'button-small',
      text: 'Добавить выбранный подарок'
    }) as HTMLButtonElement
    confirmButton.disabled = previewLoading || isSubmitting || pendingGiftSelectionId == null
    giftDisposables.push(
      on(confirmButton, 'click', () => {
        if (pendingGiftSelectionId == null) return
        applyGiftDecision(giftDecisionForPreview(preview, 'SELECT_ITEM', pendingGiftSelectionId))
      })
    )
    append(actions, confirmButton, skipButton())
    append(card, choices, actions)
    container.appendChild(card)
  }

  const renderCartPreview = () => {
    giftDisposables.splice(0).forEach((dispose) => dispose())
    const hasItems = cartSnapshot.items.size > 0
    refs.previewCard.hidden = !hasItems
    refs.previewContent.replaceChildren()
    if (!hasItems) {
      return
    }
    if (previewLoading || previewTimer !== null) {
      refs.previewContent.appendChild(el('p', { className: 'cart-summary', text: 'Считаем итог…' }))
      return
    }
    if (previewData) {
      renderGiftOffer(refs.previewContent, previewData)
      const promoDiscounts = previewData.discounts.filter((discount) => !isLoyaltyDiscount(discount.ruleType, discount.label))
      const loyaltyDiscounts = previewData.discounts.filter((discount) => isLoyaltyDiscount(discount.ruleType, discount.label))
      if (promoDiscounts.length || loyaltyDiscounts.length) {
        appendPreviewRow(refs.previewContent, 'Сумма до скидок', formatMoney(previewData.grossTotalMinor, previewData.currency))
        promoDiscounts.forEach((discount) => {
          appendPreviewRow(
            refs.previewContent,
            formatPromotionLabel(discount.label),
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
      const promotedItems = previewData.items.filter(
        (item) => item.isPromotionReward || (item.promotionAdjustment != null && item.promotionAdjustment.discountMinor > 0)
      )
      if (promotedItems.length) {
        const lineBreakdown = el('section', { className: 'cart-preview-lines' })
        lineBreakdown.appendChild(
          el('h4', {
            text: promotedItems.some((item) => item.isPromotionReward) ? 'Скидки и подарки' : 'Скидка по позициям'
          })
        )
        promotedItems.forEach((item) => {
          const adjustment = item.promotionAdjustment
          const matchingDiscount = promoDiscounts.find(
            (discount) =>
              (discount.promotionId != null && discount.promotionId === adjustment?.promotionId) ||
              (discount.ruleId != null && discount.ruleId === adjustment?.ruleId)
          )
          const line = el('article', { className: 'cart-preview-line' })
          if (item.isPromotionReward) {
            line.classList.add('cart-preview-gift-line')
          }
          const itemTitle = item.selectedOption?.name
            ? `${item.name} · ${item.selectedOption.name} × ${item.qty}`
            : `${item.name} × ${item.qty}`
          line.appendChild(el('h5', { text: item.isPromotionReward ? `Подарок · ${itemTitle}` : itemTitle }))
          appendPreviewRow(
            line,
            'Обычная стоимость',
            formatMoney(adjustment?.originalAmountMinor ?? item.lineGrossMinor, item.currency)
          )
          appendPreviewRow(
            line,
            item.isPromotionReward
              ? `${formatPromotionLabel(adjustment?.promotionTitle || matchingDiscount?.label)} · скидка 100%`
              : formatPromotionLabel(adjustment?.promotionTitle || matchingDiscount?.label),
            formatDiscount(adjustment?.discountMinor ?? item.discountMinor, item.currency)
          )
          appendPreviewRow(
            line,
            'К оплате',
            formatMoney(adjustment?.finalAmountMinor ?? item.linePayableMinor, item.currency),
            true
          )
          lineBreakdown.appendChild(line)
        })
        refs.previewContent.appendChild(lineBreakdown)
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
    if (previewFailed) {
      const retryButton = el('button', {
        className: 'button-secondary button-small',
        text: 'Повторить расчёт'
      }) as HTMLButtonElement
      giftDisposables.push(
        on(retryButton, 'click', () => {
          previewFingerprint = null
          previewFailed = false
          updateSubmitState()
        })
      )
      refs.previewContent.appendChild(retryButton)
    }
  }

  const loadCartPreview = async (fingerprint: string, tableToken: string, tableSessionId: number, tabId: number) => {
    previewAbort?.abort()
    const controller = new AbortController()
    previewAbort = controller
    previewLoading = true
    previewFailed = false
    previewMessage = ''
    renderCartPreview()
    const deps = buildApiDeps(isDebug)
    const result = await guestPreviewCart(
      backendUrl,
      {
        tableToken,
        tableSessionId,
        tabId,
        giftDecision: cartSnapshot.giftDecision,
        items: buildPreviewItems(),
        comment: cartSnapshot.commentDraft.trim() || null
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
      if (applyMenuSelectionError(result.error)) {
        updateSubmitState()
        finishItemMenuRecovery(fingerprint)
        return
      }
      previewData = null
      previewFailed = true
      previewMessage = cartSnapshot.menuSelectionIssues.size
        ? 'Не удалось обновить расчёт. Исправьте отмеченные позиции или повторите проверку.'
        : 'Не удалось рассчитать корзину. Повторите попытку.'
      updateSubmitState()
      return
    }
    const preview = result.data.preview
    const decisionContext = getGiftDecisionContext(preview)
    const hadActiveGiftDecision = cartSnapshot.giftDecision != null
    const scopeResult = decisionContext
      ? reconcileCartGiftDecisionScope(decisionContext)
      : 'none'
    if (scopeResult === 'restored') {
      return
    }
    if (scopeResult === 'cleared') {
      setMessage(GIFT_DECISION_STALE_MESSAGE)
      if (hadActiveGiftDecision) {
        return
      }
    }
    if (preview.giftDecisionStale === true) {
      setCartGiftDecision(null)
      setMessage(preview.giftDecisionMessage?.trim() || GIFT_DECISION_STALE_MESSAGE)
      return
    }
    if (
      cartSnapshot.giftDecision &&
      !giftDecisionResolvedByOffer(cartSnapshot.giftDecision, preview.giftOffer)
    ) {
      setCartGiftDecision(null)
      setMessage(GIFT_DECISION_STALE_MESSAGE)
      return
    }
    previewData = preview
    previewMessage = ''
    clearCartMenuSelectionIssues()
    updateSubmitState()
    finishItemMenuRecovery(fingerprint)
  }

  const scheduleCartPreview = () => {
    if (disposed) return
    const hadActiveGiftDecision = cartSnapshot.giftDecision != null
    if (clearExpiredCartGiftDecision() && hadActiveGiftDecision) {
      setMessage(GIFT_DECISION_STALE_MESSAGE)
      return
    }
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
    if (pendingItemMenuRecovery && pendingItemMenuRecovery.previewFingerprint !== fingerprint) {
      pendingItemMenuRecovery = null
    }
    if (
      fingerprint === previewFingerprint &&
      (previewLoading || previewTimer !== null || previewData != null || previewFailed)
    ) {
      renderCartPreview()
      return
    }
    previewFingerprint = fingerprint
    previewData = null
    previewFailed = false
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
    const hasUnresolvedMenuSelections =
      cartSnapshot.menuSelectionIssues.size > 0 || cartSnapshot.pendingValidationLineRefs.size > 0
    const tableReady = isTableReady()
    const selectedTab = getSelectedTab()
    scheduleCartPreview()
    const tableHint = tableReady ? null : resolveTableHint(tableSnapshot)
    refs.tableHint.textContent = tableHint ?? ''
    refs.tableHint.hidden = !tableHint
    let previewDisabledReason: string | null = null
    if (hasItems && tableReady && selectedTab) {
      if (previewLoading || previewTimer !== null) {
        previewDisabledReason = 'Дождитесь расчёта корзины.'
      } else if (!previewData) {
        previewDisabledReason = hasUnresolvedMenuSelections
          ? 'Исправьте отмеченные позиции и дождитесь успешного расчёта.'
          : 'Повторите расчёт корзины перед отправкой.'
      } else if (giftDecisionRequired(previewData)) {
        previewDisabledReason = 'Добавьте подарок или выберите «Пропустить подарок».'
      }
    }
    const submitDisabledReason = !hasItems
      ? 'Добавьте позиции в корзину.'
      : tableHint ?? (!selectedTab ? 'Выберите счёт (tab) для заказа.' : previewDisabledReason)
    refs.disabledReason.textContent = submitDisabledReason ?? ''
    refs.disabledReason.hidden = !submitDisabledReason
    refs.sendButton.disabled =
      isSubmitting ||
      isChatSending ||
      !hasItems ||
      !tableReady ||
      !selectedTab ||
      previewData == null ||
      previewLoading ||
      previewTimer !== null ||
      hasUnresolvedMenuSelections ||
      giftDecisionRequired(previewData)
    refs.chatButton.disabled = isSubmitting || isChatSending || !hasItems || !tableReady || hasUnresolvedMenuSelections
    updateTabsUi()
  }

  const renderItems = () => {
    const previouslyFocusedLineRef =
      document.activeElement instanceof HTMLElement && document.activeElement.classList.contains('cart-item')
        ? document.activeElement.dataset.cartLineRef ?? null
        : null
    itemDisposables.forEach((dispose) => dispose())
    itemDisposables = []
    refs.items.replaceChildren()
    if (cartSnapshot.items.size === 0) {
      refs.items.appendChild(refs.emptyState)
      if (focusCartHeading) {
        focusCartHeading = false
        window.requestAnimationFrame(() => refs.heading.focus())
      }
      return
    }
    if (cartSnapshot.menuSelectionIssues.size > 1) {
      const summary = el('p', {
        className: 'cart-menu-issue-summary',
        text: 'Некоторые позиции в корзине нужно обновить.'
      })
      summary.setAttribute('role', 'alert')
      refs.items.appendChild(summary)
    }
    const lines = Array.from(cartSnapshot.items.values())
    lines.forEach((line, lineIndex) => {
      const row = el('div', { className: 'cart-item' })
      row.dataset.cartLineRef = line.key
      row.tabIndex = -1
      const info = el('div', { className: 'cart-item-info' })
      const name = el('strong', { text: formatItemTitle(line.itemId) })
      const optionName = formatCartLineOptionName(line)
      const priceText = formatCartLinePrice(line) ?? formatItemPrice(line.itemId)
      append(info, name)
      if (optionName) {
        info.appendChild(el('span', { className: 'cart-item-option', text: `Вариант: ${optionName}` }))
      }
      if (line.preferenceNote) {
        info.appendChild(el('span', { className: 'cart-item-option', text: `Пожелание: ${line.preferenceNote}` }))
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
      const storedItemName = safeItemName(line.itemId)
      const itemName = storedItemName ?? 'позиция'
      const duplicateItemLineSuffix =
        storedItemName && lines.filter((candidate) => safeItemName(candidate.itemId) === storedItemName).length > 1
          ? `, строка ${lineIndex + 1}`
          : ''
      removeButton.setAttribute('aria-label', `Удалить из корзины: ${itemName}`)
      append(controls, qtyControls, removeButton)
      const issue = cartSnapshot.menuSelectionIssues.get(line.key)
      const isPendingValidation = cartSnapshot.pendingValidationLineRefs.has(line.key)
      if (issue || isPendingValidation) {
        row.classList.add('cart-item-menu-issue')
        const warning = el('div', { className: 'cart-line-warning' })
        warning.id = `cart-line-warning-${lineIndex + 1}`
        warning.setAttribute('role', issue ? 'alert' : 'status')
        warning.appendChild(
          el('strong', {
            text: issue ? 'Требуется обновление' : 'Проверяем новый вариант'
          })
        )
        warning.appendChild(
          el('p', {
            text: issue
              ? cartMenuSelectionIssueCopy(issue, line)
              : 'Новый вариант будет подтверждён после успешного расчёта корзины.'
          })
        )
        const recoveryActions = el('div', { className: 'cart-line-warning-actions' })
        if (issue?.selectionKind === 'OPTION') {
          const replaceButton = el('button', {
            className: 'button-small',
            text: 'Выбрать другой вариант'
          }) as HTMLButtonElement
          replaceButton.setAttribute('aria-label', `Выбрать другой вариант для позиции ${itemName}`)
          recoveryActions.appendChild(replaceButton)
          itemDisposables.push(on(replaceButton, 'click', () => onNavigateOptionReplacement(line.key)))
        } else if (issue?.selectionKind === 'ITEM') {
          const menuButton = el('button', {
            className: 'button-small',
            text: 'Удалить и выбрать другую'
          }) as HTMLButtonElement
          menuButton.setAttribute(
            'aria-label',
            storedItemName
              ? `Удалить «${storedItemName}» и выбрать другую позицию${duplicateItemLineSuffix}`
              : `Удалить позицию ${lineIndex + 1} и выбрать другую позицию`
          )
          recoveryActions.appendChild(menuButton)
          itemDisposables.push(
            on(menuButton, 'click', () => {
              setMessage('')
              pendingFocusLineRef = lines[lineIndex + 1]?.key ?? null
              focusCartHeading = pendingFocusLineRef == null
              const result = setCartLineQty(line.key, 0)
              if (!result.ok) {
                return
              }
              pendingItemMenuRecovery = {
                venueId: tableSnapshot.venueId,
                tableSessionId: tableSnapshot.tableSessionId,
                tableToken: tableSnapshot.tableToken,
                tabId: getSelectedTab()?.id ?? null,
                cartMutationSignature,
                previewFingerprint: cartSnapshot.items.size ? previewFingerprint : null
              }
              if (!cartSnapshot.items.size) {
                finishItemMenuRecovery(null)
              }
            })
          )
        }
        const recoveryRemoveButton = el('button', {
          className: 'button-small button-secondary',
          text: 'Удалить из корзины'
        }) as HTMLButtonElement
        recoveryRemoveButton.setAttribute(
          'aria-label',
          issue?.selectionKind === 'ITEM'
            ? storedItemName
              ? `Удалить «${storedItemName}» из корзины${duplicateItemLineSuffix}`
              : `Удалить позицию ${lineIndex + 1} из корзины`
            : `Удалить из корзины: ${itemName}`
        )
        recoveryActions.appendChild(recoveryRemoveButton)
        itemDisposables.push(
          on(recoveryRemoveButton, 'click', () => {
            pendingFocusLineRef =
              issue?.selectionKind === 'ITEM'
                ? lines[lineIndex + 1]?.key ?? null
                : lines[lineIndex + 1]?.key ?? lines[lineIndex - 1]?.key ?? null
            focusCartHeading = pendingFocusLineRef == null
            setCartLineQty(line.key, 0)
          })
        )
        warning.appendChild(recoveryActions)
        info.appendChild(warning)
        row.setAttribute('aria-describedby', warning.id)
      }
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
            priceDeltaMinor: line.priceDeltaMinor,
            preferenceNote: line.preferenceNote
          })
          if (!result.ok) {
            setMessage(result.reason === 'limit' ? 'Можно выбрать не более 50 разных позиций.' : 'Некорректное значение.')
          }
        }),
        on(removeButton, 'click', () => {
          setMessage('')
          pendingFocusLineRef = lines[lineIndex + 1]?.key ?? lines[lineIndex - 1]?.key ?? null
          focusCartHeading = pendingFocusLineRef == null
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
    })
    const focusLineRef = pendingFocusLineRef ?? previouslyFocusedLineRef
    if (focusLineRef) {
      const target = refs.items.querySelector<HTMLElement>(
        `.cart-item[data-cart-line-ref="${CSS.escape(focusLineRef)}"]`
      )
      if (target) {
        window.requestAnimationFrame(() => {
          if (!target.isConnected) return
          target.focus()
          if (pendingFocusLineRef === focusLineRef) {
            pendingFocusLineRef = null
          }
        })
      }
    } else if (focusCartHeading) {
      focusCartHeading = false
      window.requestAnimationFrame(() => refs.heading.focus())
    }
  }

  const validateBeforeSubmit =
    (requireAuthoritativePreview = true):
      | { ok: true; comment: string | null; tabId: number; tableSessionId: number }
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
    if (requireAuthoritativePreview) {
      if (previewLoading || previewTimer !== null) {
        return { ok: false, reason: 'Дождитесь расчёта корзины.' }
      }
      if (!previewData) {
        return { ok: false, reason: 'Повторите расчёт корзины перед отправкой.' }
      }
      if (giftDecisionRequired(previewData)) {
        return { ok: false, reason: 'Добавьте подарок или выберите «Пропустить подарок».' }
      }
    }
    return { ok: true, comment: commentValue ? commentValue : null, tabId: selectedTab.id, tableSessionId }
  }

  const buildSubmitItems = () =>
    Array.from(cartSnapshot.items.values())
      .map((line) => ({
        cartLineRef: line.key,
        itemId: line.itemId,
        qty: line.qty,
        ...(line.selectedOptionId != null ? { selectedOptionId: line.selectedOptionId } : {}),
        ...(line.preferenceNote ? { preferenceNote: line.preferenceNote } : {})
      }))
      .sort(compareCartRequestItems)

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
    const giftDecision = cartSnapshot.giftDecision
    const fingerprint = buildCanonicalSubmitFingerprint(
      currentTelegramUserId,
      tableSnapshot.venueId,
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
      previewFingerprint: previewData?.pricingFingerprint ?? null,
      giftDecision,
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
      if (applyMenuSelectionError(result.error)) {
        updateSubmitState()
        return
      }
      if (result.error.status === 409 && code === ApiErrorCodes.ORDER_IDEMPOTENCY_PAYLOAD_MISMATCH) {
        resetSubmitIdempotency()
        showSubmitError(result.error)
        updateSubmitState()
        return
      }
      if (result.error.status === 409 && code === ApiErrorCodes.ORDER_IDEMPOTENCY_REPLAY_UNVERIFIABLE) {
        showSubmitError(result.error, [
          {
            label: 'Проверить активный заказ',
            kind: 'secondary',
            onClick: onNavigateOrder
          },
          {
            label: 'Отправить как новый заказ',
            kind: 'primary',
            onClick: () => {
              resetSubmitIdempotency()
              void handleSubmit()
            }
          }
        ])
        updateSubmitState()
        return
      }
      showSubmitError(result.error)
      updateSubmitState()
      return
    }
    resetSubmitIdempotency()
    const submitted =
      result.data.submitted ??
      (result.data.orderId != null && result.data.batchId != null)
    if (!submitted) {
      previewLoading = false
      previewFailed = false
      previewMessage = ''
      if (
        result.data.pricing.giftDecisionStale === true ||
        (giftDecision && !giftDecisionResolvedByOffer(giftDecision, result.data.pricing.giftOffer))
      ) {
        setCartGiftDecision(null)
      } else {
        previewData = result.data.pricing
        previewFingerprint = buildPreviewFingerprint(
          tableToken,
          validation.tableSessionId,
          validation.tabId
        )
        updateSubmitState()
      }
      setMessage(result.data.pricing.giftDecisionMessage?.trim() || GIFT_DECISION_STALE_MESSAGE)
      return
    }
    const wasRecalculated = result.data.recalculated === true
    const currentTableSessionId = tableSnapshot.tableSessionId
    const currentTabId = getSelectedTab()?.id ?? null
    const currentFingerprint =
      currentTableSessionId != null && currentTabId != null
        ? buildCanonicalSubmitFingerprint(
            getTelegramContext().telegramUserId,
            tableSnapshot.venueId,
            currentTableSessionId,
            currentTabId,
            refs.commentInput.value.trim() || null,
            cartSnapshot.items.values()
          )
        : null
    if (currentFingerprint !== fingerprint) {
      setMessage(CART_CHANGED_DURING_SUBMIT_MESSAGE)
      updateSubmitState()
      return
    }
    clearCart()
    refs.commentInput.value = ''
    refs.commentCounter.textContent = `0/${MAX_COMMENT_LENGTH}`
    updateSubmitState()
    showToast(wasRecalculated ? 'Условия акции изменились. Итог корзины пересчитан.' : 'Отправлено в заказ')
    onNavigateOrder()
  }

  const handleChatOrder = () => {
    if (isSubmitting || isChatSending) return
    setMessage('')
    setChatMessage('')
    hideSubmitError()
    const validation = validateBeforeSubmit(false)
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
    const nextMutationSignature = cartBusinessMutationSignature(snapshot)
    if (
      pendingItemMenuRecovery &&
      pendingItemMenuRecovery.cartMutationSignature !== nextMutationSignature
    ) {
      pendingItemMenuRecovery = null
    }
    if (nextMutationSignature !== cartMutationSignature) {
      cartMutationSignature = nextMutationSignature
      resetSubmitIdempotency()
    }
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
    const previousVenueId = tableSnapshot.venueId
    if (
      pendingItemMenuRecovery &&
      (pendingItemMenuRecovery.tableSessionId !== snapshot.tableSessionId ||
        pendingItemMenuRecovery.venueId !== snapshot.venueId ||
        pendingItemMenuRecovery.tableToken !== snapshot.tableToken)
    ) {
      pendingItemMenuRecovery = null
    }
    tableSnapshot = snapshot
    if (
      (previousTableSessionId != null && snapshot.tableSessionId !== previousTableSessionId) ||
      (previousVenueId != null && snapshot.venueId !== previousVenueId)
    ) {
      setCartGiftDecision(null)
    }
    if (snapshot.tableSessionId !== previousTableSessionId || snapshot.venueId !== previousVenueId) {
      resetSubmitIdempotency()
    }
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
    pendingItemMenuRecovery = null
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
    giftDisposables.forEach((dispose) => dispose())
    disposables.forEach((dispose) => dispose())
  }
}
