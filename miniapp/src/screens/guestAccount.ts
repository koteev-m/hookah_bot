import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  guestBuildVisitRepeatPlan,
  guestGetFavoriteVenues,
  guestGetTabs,
  guestRemoveFavoriteVenue,
  guestGetVisitDetail,
  guestGetVisits,
  guestSubmitVisitFeedback
} from '../shared/api/guestApi'
import type {
  GuestBookingListResponse,
  GuestFavoriteVenueDto,
  GuestVisitFeedbackDto,
  GuestVisitDetailDto,
  GuestVisitListItemDto,
  GuestVisitOrderDto,
  GuestVisitRepeatPlanResponse
} from '../shared/api/guestDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { addLinesToCart } from '../shared/state/cartStore'
import { getSelectedGuestTabId, setSelectedGuestTabId } from '../shared/state/guestTabSelection'
import { updateItemCache } from '../shared/state/itemCache'
import type { TableContextSnapshot } from '../shared/state/tableContext'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'
import { renderGuestProfile, type GuestProfileDraft } from './guestProfile'
import { renderGuestLoyalty } from './guestLoyalty'
import { parsePositiveInt } from '../shared/parse'

type AccountSection = 'home' | 'profile' | 'history' | 'favorites' | 'loyalty'

const FEEDBACK_TAGS: Array<{ slug: string; label: string }> = [
  { slug: 'service', label: 'Сервис' },
  { slug: 'hookah_quality', label: 'Кальян' },
  { slug: 'taste', label: 'Вкус' },
  { slug: 'speed', label: 'Скорость' },
  { slug: 'atmosphere', label: 'Атмосфера' },
  { slug: 'cleanliness', label: 'Чистота' },
  { slug: 'booking', label: 'Бронь' },
  { slug: 'price', label: 'Цена' }
]

export type GuestAccountScreenOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  profileDraft: GuestProfileDraft
  onOpenHelp: () => void
  tableContext: TableContextSnapshot
  onBack: () => void
  onOpenBookings: () => void
  onOpenVenue: (venueId: number) => void
  onBookVenue: (venueId: number) => void
  onAskVenue: (venueId: number) => void
  onNavigateCart: () => void
  selectedVenueId: number | null
}

function requestDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString('ru-RU')
}

function formatMoney(amountMinor: number | null | undefined, currency: string | null | undefined): string | null {
  if (amountMinor === null || amountMinor === undefined || !currency) {
    return null
  }
  return formatPrice(amountMinor, currency)
}

function errorText(error: ApiErrorInfo): string {
  const code = normalizeErrorCode(error)
  if (code === REQUEST_ABORTED_CODE) {
    return ''
  }
  if (error.message) {
    return error.message
  }
  return 'Не удалось загрузить данные. Попробуйте ещё раз.'
}

function renderAccountHome(
  root: HTMLElement,
  onOpenBookings: () => void,
  onOpenHelp: () => void
) {
  const section = el('section', { className: 'card guest-account-home' })
  const rows: Array<[string, () => void]> = [
    ['Личные данные', () => { window.location.hash = '#/account/profile' }],
    ['Мои брони', onOpenBookings],
    ['История', () => { window.location.hash = '#/account/history' }],
    ['Избранное', () => { window.location.hash = '#/account/favorites' }],
    ['Лояльность', () => { window.location.hash = '#/account/loyalty' }],
    ['Помощь', onOpenHelp]
  ]
  section.appendChild(el('h1', { text: 'Профиль' }))
  for (const [label, action] of rows) {
    const button = el('button', { className: 'button-secondary guest-account-row', text: label })
    const arrow = el('span', { text: '›' })
    arrow.setAttribute('aria-hidden', 'true')
    button.appendChild(arrow)
    button.addEventListener('click', action)
    section.appendChild(button)
  }
  root.replaceChildren(section)
  return () => undefined
}

function renderVisitSummary(item: GuestVisitListItemDto, onOpen: (visitId: number) => void, orderHistory: boolean) {
  const card = el('article', { className: 'card' })
  const title = el('h3', { text: item.venueName })
  const date = el('p', { className: 'venue-order-sub', text: formatDateTime(item.occurredAt) })
  const total = formatMoney(item.totalMinor, item.currency)
  const details = [
    item.venueCity ? `Город: ${item.venueCity}` : null,
    total ? `Итого: ${total}` : null,
    item.orderLabels.length > 0 ? `Заказы: ${item.orderLabels.join(', ')}` : null,
    !orderHistory && item.hasBooking ? 'Посещение по брони' : null
  ].filter((value): value is string => Boolean(value))
  const meta = el('p', { className: 'venue-order-sub', text: details.join(' · ') })
  const openButton = el('button', { className: 'button-secondary', text: 'Подробнее' }) as HTMLButtonElement
  append(card, title, date, meta.textContent ? meta : null, openButton)
  return {
    card,
    dispose: on(openButton, 'click', () => onOpen(item.visitId))
  }
}

function renderVisitOrder(order: GuestVisitOrderDto) {
  const card = el('article', { className: 'card' })
  const title = el('h3', {
    text: order.displayNumber ? `Заказ №${order.displayNumber}` : `Заказ #${order.orderId}`
  })
  const total = formatMoney(order.totalMinor, order.currency)
  const meta = el('p', {
    className: 'venue-order-sub',
    text: [order.displayDate ? `Дата: ${order.displayDate}` : null, total ? `Итого: ${total}` : null]
      .filter((value): value is string => Boolean(value))
      .join(' · ') || 'Состав заказа'
  })
  const itemList = el('div', { className: 'venue-order-items' })
  const orderItems = order.items ?? []
  orderItems.forEach((item) => {
    const itemName = item.itemName?.trim() || (item.itemId ? `Позиция #${item.itemId}` : 'Позиция')
    const qty = item.qty ?? 0
    const itemDetails = [
      item.isPromotionReward ? `Подарок по акции · ${itemName}` : itemName,
      item.selectedOption?.name ?? null,
      item.preferenceNote ? `Пожелание: ${item.preferenceNote}` : null,
      item.promotionLinkRole === 'TRIGGER' && item.promotionLabel
        ? `Условие акции «${item.promotionLabel}»`
        : null
    ].filter((value): value is string => Boolean(value))
    const inactiveState = item.isExcluded
      ? `Исключено из счёта${item.excludedReasonText ? `: ${item.excludedReasonText}` : ''}`
      : item.itemStatus?.toUpperCase() === 'CANCELED'
        ? `Отменено${item.canceledReasonText ? `: ${item.canceledReasonText}` : ''}`
        : null
    const regularTotal = formatMoney(item.totalMinor, item.currency)
    const giftOriginalMinor =
      item.isPromotionReward && item.priceMinor != null && qty > 0 ? item.priceMinor * qty : null
    const giftPriceSummary =
      item.isPromotionReward && giftOriginalMinor != null && item.currency
        ? ` — обычная стоимость ${formatMoney(giftOriginalMinor, item.currency)} · акция −${formatMoney(
            item.promoDiscountMinor ?? giftOriginalMinor,
            item.currency
          )} · итого ${regularTotal ?? formatMoney(0, item.currency)}`
        : regularTotal
          ? ` — ${regularTotal}`
          : ''
    const line = el('p', {
      className: 'venue-order-sub',
      text: `${itemDetails.join(' · ')} ×${qty}${giftPriceSummary}${inactiveState ? ` · ${inactiveState}` : ''}`
    })
    append(itemList, line)
  })
  const discountList = el('div', { className: 'venue-order-items' })
  const promotionDiscounts = order.promotionDiscounts ?? []
  promotionDiscounts.forEach((discount) => {
    append(
      discountList,
      el('p', {
        className: 'venue-order-sub',
        text:
          discount.originalAmountMinor != null && discount.finalAmountMinor != null
            ? `${discount.label}: ${formatPrice(discount.originalAmountMinor, discount.currency)} − ${formatPrice(discount.discountMinor, discount.currency)} = ${formatPrice(discount.finalAmountMinor, discount.currency)}${discount.isActive === false ? ' · больше не действует' : ''}`
            : `${discount.label}: −${formatPrice(discount.discountMinor, discount.currency)}${discount.isActive === false ? ' · больше не действует' : ''}`
      })
    )
  })
  append(card, title, meta, itemList, discountList)
  return card
}

function renderVisitRepeatBlock(
  visit: GuestVisitDetailDto,
  order: GuestVisitOrderDto,
  backendUrl: string,
  isDebug: boolean,
  signal: AbortSignal,
  tableContext: TableContextSnapshot,
  onNavigateCart: () => void
) {
  const section = el('section', { className: 'card visit-repeat-card' })
  const title = el('h3', { text: 'Повторить заказ' })
  const message = el('p', { className: 'status', text: '' })
  const repeatButton = el('button', { className: 'button-secondary', text: 'Повторить заказ' }) as HTMLButtonElement
  let planPending = false
  let added = false
  const disposables: Array<() => void> = []

  const showContextError = (): boolean => {
    const activeTable =
      tableContext.status === 'resolved' &&
      tableContext.tableSessionActive &&
      tableContext.tableSessionId != null &&
      tableContext.venueId != null
    if (!activeTable) {
      message.textContent = 'Чтобы повторить заказ, отсканируйте QR на столе в этом заведении.'
      return true
    }
    if (tableContext.venueId !== visit.venueId) {
      message.textContent = 'Этот заказ можно повторить только в том же заведении.'
      return true
    }
    if (!tableContext.orderAllowed) {
      message.textContent = 'Заведение сейчас недоступно для заказа.'
      return true
    }
    return false
  }

  const renderPlan = (plan: GuestVisitRepeatPlanResponse) => {
    const content: Node[] = [
      title,
      el('p', {
        className: 'venue-order-sub',
        text: 'Добавим доступные позиции в корзину по текущим ценам.'
      })
    ]
    if (plan.eligibleLines.length > 0) {
      const eligibleTitle = el('h4', { text: 'Доступно' })
      const eligibleList = el('div', { className: 'venue-order-items repeat-eligible-lines' })
      plan.eligibleLines.forEach((line) => {
        const details = [
          line.itemName,
          line.selectedOption?.name ?? null,
          line.preferenceNote ? `Пожелание: ${line.preferenceNote}` : null
        ].filter((value): value is string => Boolean(value))
        append(
          eligibleList,
          el('p', {
            className: 'venue-order-sub',
            text: `${details.join(' · ')} ×${line.quantity} — ${formatPrice(
              line.currentLineTotal.amountMinor,
              line.currentLineTotal.currency
            )}`
          })
        )
      })
      content.push(eligibleTitle, eligibleList)
    }
    if (plan.skippedLines.length > 0) {
      const skippedTitle = el('h4', { text: 'Не получится повторить' })
      const skippedList = el('div', { className: 'venue-order-items repeat-skipped-lines' })
      plan.skippedLines.forEach((line) => {
        const option = line.selectedOptionName ? ` · ${line.selectedOptionName}` : ''
        append(
          skippedList,
          el('p', {
            className: 'venue-order-sub',
            text: `${line.itemName}${option} ×${line.quantity} — ${line.message}`
          })
        )
      })
      content.push(skippedTitle, skippedList)
    }
    if (plan.eligibleLines.length === 0) {
      content.push(
        el('p', {
          className: 'status',
          text: 'Сейчас ни одну позицию из этого заказа повторить нельзя.'
        })
      )
      section.replaceChildren(...content)
      return
    }

    content.push(
      el('p', {
        className: 'visit-repeat-total',
        text: `Итого по текущим ценам: ${formatPrice(plan.currentTotal.amountMinor, plan.currentTotal.currency)}`
      })
    )
    const addButton = el('button', { text: 'Добавить в корзину' }) as HTMLButtonElement
    const addMessage = el('p', { className: 'status', text: '' })
    const cartButton = el('button', {
      className: 'button-secondary',
      text: 'Перейти в корзину'
    }) as HTMLButtonElement
    cartButton.hidden = true
    disposables.push(
      on(addButton, 'click', () => {
        if (added || addButton.disabled) return
        addButton.disabled = true
        updateItemCache(
          plan.eligibleLines.map((line) => ({
            itemId: line.itemId,
            name: line.itemName,
            priceMinor: line.currentItemPrice.amountMinor,
            currency: line.currentItemPrice.currency,
            options: line.selectedOption
              ? [
                  {
                    id: line.selectedOption.optionId,
                    name: line.selectedOption.name,
                    priceDeltaMinor: line.selectedOption.currentPriceDelta.amountMinor
                  }
                ]
              : []
          }))
        )
        const result = addLinesToCart(
          plan.eligibleLines.map((line) => ({
            itemId: line.itemId,
            qty: line.quantity,
            selectedOptionId: line.selectedOption?.optionId ?? null,
            selectedOptionName: line.selectedOption?.name ?? null,
            priceDeltaMinor: line.selectedOption?.currentPriceDelta.amountMinor ?? null,
            preferenceNote: line.preferenceNote ?? null
          }))
        )
        if (!result.ok) {
          addButton.disabled = false
          addMessage.textContent =
            result.reason === 'limit'
              ? 'В корзине недостаточно места для этих позиций.'
              : 'Не удалось добавить позиции в корзину.'
          return
        }
        added = true
        addMessage.textContent = 'Доступные позиции добавлены в корзину.'
        cartButton.hidden = false
      }),
      on(cartButton, 'click', onNavigateCart)
    )
    content.push(addButton, cartButton, addMessage)
    section.replaceChildren(...content)
  }

  disposables.push(
    on(repeatButton, 'click', async () => {
      if (planPending || added || showContextError()) return
      const tableSessionId = tableContext.tableSessionId
      if (!tableSessionId) return
      planPending = true
      repeatButton.disabled = true
      repeatButton.textContent = 'Готовим preview...'
      message.textContent = ''
      const deps = requestDeps(isDebug)
      const tabsResult = await guestGetTabs(backendUrl, tableSessionId, deps, signal)
      if (!tabsResult.ok) {
        planPending = false
        repeatButton.disabled = false
        repeatButton.textContent = 'Повторить заказ'
        if (tabsResult.error.code !== REQUEST_ABORTED_CODE) {
          message.textContent = 'Не удалось проверить выбранный счёт. Попробуйте ещё раз.'
        }
        return
      }
      const activeTabs = tabsResult.data.tabs.filter(
        (tab) => tab.status === 'ACTIVE' && tab.tableSessionId === tableSessionId
      )
      const storedTabId = getSelectedGuestTabId(tableSessionId)
      const selectedTab =
        activeTabs.find((tab) => tab.id === storedTabId) ??
        activeTabs.find((tab) => tab.type === 'PERSONAL') ??
        activeTabs[0]
      if (!selectedTab) {
        planPending = false
        repeatButton.disabled = false
        repeatButton.textContent = 'Повторить заказ'
        message.textContent = 'Выберите доступный счёт для текущего стола.'
        return
      }
      setSelectedGuestTabId(tableSessionId, selectedTab.id)
      const result = await guestBuildVisitRepeatPlan(
        backendUrl,
        visit.visitId,
        {
          tableSessionId,
          tabId: selectedTab.id,
          orderId: visit.orders.length > 1 ? order.orderId : undefined
        },
        deps,
        signal
      )
      planPending = false
      if (!result.ok) {
        repeatButton.disabled = false
        repeatButton.textContent = 'Повторить заказ'
        if (result.error.code !== REQUEST_ABORTED_CODE) {
          message.textContent =
            result.error.status === 403
              ? 'Этот счёт недоступен для повторения заказа.'
              : 'Не удалось подготовить повтор заказа. Попробуйте ещё раз.'
        }
        return
      }
      if (result.data.venueId !== visit.venueId || result.data.sourceOrderId !== order.orderId) {
        repeatButton.disabled = false
        repeatButton.textContent = 'Повторить заказ'
        message.textContent = 'Не удалось безопасно проверить повтор заказа.'
        return
      }
      renderPlan(result.data)
    })
  )

  append(section, title, repeatButton, message)
  return {
    element: section,
    dispose: () => disposables.forEach((dispose) => dispose())
  }
}

function renderSubmittedFeedback(section: HTMLElement, feedback: GuestVisitFeedbackDto, successText?: string) {
  section.replaceChildren()
  const title = el('h3', { text: 'Отзыв' })
  const nodes: Node[] = [
    title,
    el('p', {
      className: 'venue-order-sub',
      text: feedback.rating ? `Вы оценили визит: ${feedback.rating}/5.` : 'Отзыв сохранён.'
    })
  ]
  if (successText) {
    nodes.splice(1, 0, el('p', { className: 'status', text: successText }))
  }
  if (feedback.rating === 5 && feedback.publicReviewUrl) {
    const reviewLink = el('a', {
      className: 'button-secondary feedback-public-review-link',
      text: 'Оставить отзыв на Яндекс.Картах'
    }) as HTMLAnchorElement
    reviewLink.href = feedback.publicReviewUrl
    reviewLink.target = '_blank'
    reviewLink.rel = 'noopener noreferrer'
    nodes.push(
      el('p', { className: 'venue-order-sub', text: 'Ссылка откроется во внешнем окне.' }),
      reviewLink
    )
  }
  append(section, ...nodes)
}

function renderVisitFeedbackBlock(
  visit: GuestVisitDetailDto,
  backendUrl: string,
  isDebug: boolean,
  signal: AbortSignal
) {
  const section = el('section', { className: 'card' })
  const feedback = visit.feedback
  if (!feedback?.eligible) {
    return { element: section, dispose: () => undefined, hidden: true }
  }
  if (feedback.submitted) {
    renderSubmittedFeedback(section, feedback)
    return { element: section, dispose: () => undefined, hidden: false }
  }

  const disposables: Array<() => void> = []
  const title = el('h3', { text: 'Отзыв' })
  const bookingOnlyFeedbackText =
    visit.booking && visit.orders.length === 0 ? 'Можно оценить бронь, встречу и обслуживание.' : null
  const status = el('p', { className: 'status', text: '' })
  const openButton = el('button', { className: 'button-secondary', text: 'Оценить визит' }) as HTMLButtonElement
  append(section, title)
  if (bookingOnlyFeedbackText) {
    append(section, el('p', { className: 'venue-order-sub', text: bookingOnlyFeedbackText }))
  }
  append(section, openButton, status)

  const renderForm = () => {
    let selectedRating = 0
    const selectedTags = new Set<string>()
    const ratingRow = el('div', { className: 'button-row feedback-rating-row' })
    const tagRow = el('div', { className: 'button-row feedback-tag-row' })
    const helper = el('p', { className: 'venue-order-sub feedback-helper', text: '' })
    const comment = document.createElement('textarea')
    comment.className = 'venue-textarea'
    comment.maxLength = 1000
    comment.placeholder = 'Комментарий'
    const submitButton = el('button', { text: 'Сохранить отзыв' }) as HTMLButtonElement
    submitButton.disabled = true
    const cancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
    const actions = el('div', { className: 'venue-inline-actions' })
    const message = el('p', { className: 'status', text: '' })
    let ratingButtons: HTMLButtonElement[] = []

    const updateRatingState = () => {
      submitButton.disabled = selectedRating === 0
      helper.textContent =
        selectedRating > 0 && selectedRating <= 3
          ? 'Жаль, что визит прошёл не идеально. Расскажите, что было не так — заведение сможет разобраться.'
          : ''
      helper.hidden = helper.textContent === ''
      comment.placeholder = selectedRating > 0 && selectedRating <= 3 ? 'Что было не так?' : 'Комментарий'
      ratingButtons.forEach((item, index) => {
        const active = index + 1 === selectedRating
        item.dataset.active = String(active)
        item.setAttribute('aria-pressed', String(active))
      })
    }

    ratingButtons = [1, 2, 3, 4, 5].map((rating) => {
      const button = el('button', {
        className: 'button-secondary feedback-rating-button',
        text: String(rating)
      }) as HTMLButtonElement
      button.dataset.active = 'false'
      button.setAttribute('aria-pressed', 'false')
      disposables.push(
        on(button, 'click', () => {
          selectedRating = rating
          updateRatingState()
          message.textContent = ''
        })
      )
      append(ratingRow, button)
      return button
    })

    FEEDBACK_TAGS.forEach((tag) => {
      const button = el('button', {
        className: 'button-secondary feedback-tag-button',
        text: tag.label
      }) as HTMLButtonElement
      button.dataset.active = 'false'
      button.setAttribute('aria-pressed', 'false')
      disposables.push(
        on(button, 'click', () => {
          if (selectedTags.has(tag.slug)) {
            selectedTags.delete(tag.slug)
            button.dataset.active = 'false'
            button.setAttribute('aria-pressed', 'false')
            message.textContent = ''
            return
          }
          if (selectedTags.size >= 5) {
            message.textContent = 'Можно выбрать до 5 тегов.'
            return
          }
          selectedTags.add(tag.slug)
          button.dataset.active = 'true'
          button.setAttribute('aria-pressed', 'true')
          message.textContent = ''
        })
      )
      append(tagRow, button)
    })
    helper.hidden = true

    disposables.push(
      on(cancelButton, 'click', () => {
        const nodes: Node[] = [title]
        if (bookingOnlyFeedbackText) {
          nodes.push(el('p', { className: 'venue-order-sub', text: bookingOnlyFeedbackText }))
        }
        nodes.push(openButton, status)
        section.replaceChildren(...nodes)
      }),
      on(submitButton, 'click', async () => {
        if (!selectedRating) {
          message.textContent = 'Выберите оценку от 1 до 5.'
          return
        }
        submitButton.disabled = true
        message.textContent = 'Сохраняем...'
        const result = await guestSubmitVisitFeedback(
          backendUrl,
          visit.visitId,
          {
            rating: selectedRating,
            tags: Array.from(selectedTags),
            comment: comment.value.trim() || null
          },
          requestDeps(isDebug),
          signal
        )
        submitButton.disabled = false
        if (!result.ok) {
          if (result.error.code === REQUEST_ABORTED_CODE) return
          message.textContent = errorText(result.error)
          return
        }
        visit.feedback = result.data.feedback
        const submittedRating = result.data.feedback.rating ?? selectedRating
        const successText =
          submittedRating <= 3
            ? 'Спасибо, отзыв сохранён. Мы передали его заведению.'
            : submittedRating === 5
              ? 'Спасибо за высокую оценку!'
              : 'Спасибо, отзыв сохранён.'
        renderSubmittedFeedback(section, result.data.feedback, successText)
      })
    )

    append(actions, submitButton, cancelButton)
    const formNodes: Node[] = [
      title,
    ]
    if (bookingOnlyFeedbackText) {
      formNodes.push(el('p', { className: 'venue-order-sub', text: bookingOnlyFeedbackText }))
    }
    formNodes.push(
      el('p', { className: 'venue-order-sub', text: 'Оценка обязательна, теги и комментарий — по желанию.' }),
      ratingRow,
      tagRow,
      helper,
      comment,
      actions,
      message
    )
    section.replaceChildren(...formNodes)
  }

  disposables.push(on(openButton, 'click', renderForm))
  return {
    element: section,
    dispose: () => disposables.forEach((dispose) => dispose()),
    hidden: false
  }
}

function renderVisitDetail(
  root: HTMLElement,
  visit: GuestVisitDetailDto,
  backendUrl: string,
  isDebug: boolean,
  signal: AbortSignal,
  tableContext: TableContextSnapshot,
  onNavigateCart: () => void,
  onBack: () => void,
  orderHistory: boolean
) {
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const heading = el('h2', { text: visit.venueName })
  const total = formatMoney(visit.totalMinor, visit.currency)
  const meta = el('p', {
    className: 'venue-order-sub',
    text: [formatDateTime(visit.occurredAt), total ? `Итого: ${total}` : null]
      .filter((value): value is string => Boolean(value))
      .join(' · ')
  })
  const backButton = el('button', { text: '← Назад к истории' }) as HTMLButtonElement
  append(header, heading, meta, backButton)
  append(wrapper, header)
  const feedbackBlock = renderVisitFeedbackBlock(visit, backendUrl, isDebug, signal)
  if (!orderHistory && !feedbackBlock.hidden) {
    append(wrapper, feedbackBlock.element)
  }
  const repeatBlocks = visit.orders.map((order) => {
    const repeatBlock = renderVisitRepeatBlock(
      visit,
      order,
      backendUrl,
      isDebug,
      signal,
      tableContext,
      onNavigateCart
    )
    append(wrapper, renderVisitOrder(order), repeatBlock.element)
    return repeatBlock
  })
  if (visit.orders.length === 0) {
    const emptyText = visit.booking
      ? 'Посещение по брони. Заказов в этом визите нет.'
      : 'В этом визите нет заказов для отображения.'
    append(wrapper, el('section', { className: 'card', text: emptyText }))
  }
  root.replaceChildren(wrapper)
  const dispose = on(backButton, 'click', onBack)
  return () => {
    dispose()
    feedbackBlock.dispose()
    repeatBlocks.forEach((block) => block.dispose())
  }
}

function renderHistorySection(
  root: HTMLElement,
  backendUrl: string,
  isDebug: boolean,
  tableContext: TableContextSnapshot,
  onNavigateCart: () => void,
  onBack: () => void
) {
  const controller = new AbortController()
  const deps = requestDeps(isDebug)
  const params = new URLSearchParams(window.location.hash.split('?')[1] ?? '')
  const kind = params.get('kind') === 'orders' ? 'orders' : params.get('kind') === 'bookings' ? 'bookings' : 'visits'
  const visitId = parsePositiveInt(params.get('visit'))
  const historyHash = (visit?: number) => {
    const query = new URLSearchParams()
    if (kind !== 'visits') query.set('kind', kind)
    if (visit) query.set('visit', String(visit))
    return `#/account/history${query.size ? `?${query}` : ''}`
  }
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const back = el('button', { text: visitId ? '← Назад к истории' : 'К профилю', className: 'button-secondary button-small' })
  back.addEventListener('click', onBack)
  const tabs = el('div', { className: 'venue-inline-actions guest-section-tabs' })
  for (const [id, label] of [['bookings', 'Бронирования'], ['visits', 'Посещения'], ['orders', 'Заказы']] as const) {
    const button = el('button', { text: label, className: 'button-secondary button-small' })
    button.setAttribute('aria-pressed', String(kind === id))
    button.addEventListener('click', () => { window.location.hash = id === 'visits' ? '#/account/history' : `#/account/history?kind=${id}` })
    tabs.appendChild(button)
  }
  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  const list = el('div', { className: 'venue-settings' })
  const more = el('button', { text: 'Показать ещё', className: 'button-secondary button-small' })
  append(header, back, el('h1', { text: 'История' }), visitId ? null : tabs)
  append(wrapper, header, status, list, more)
  root.replaceChildren(wrapper)
  let cursor: number | null = null
  let detailDispose: (() => void) | undefined
  let loading = false

  const load = async () => {
    if (loading) return
    loading = true
    more.hidden = true
    status.textContent = 'Загружаем историю…'
    if (visitId) {
      const result = await guestGetVisitDetail(backendUrl, visitId, deps, controller.signal)
      if (controller.signal.aborted) return
      if (result.ok) {
        detailDispose = renderVisitDetail(root, result.data.visit, backendUrl, isDebug,
          controller.signal, tableContext, onNavigateCart, onBack, kind === 'orders')
        return
      }
    } else if (kind === 'bookings') {
      const suffix = cursor === null ? '' : `?beforeId=${cursor}`
      const result = await requestApi<GuestBookingListResponse>(backendUrl, `/api/guest/bookings/archive${suffix}`, { signal: controller.signal }, deps)
      if (controller.signal.aborted) return
      if (result.ok) {
        for (const booking of result.data.items) {
          const card = el('article', { className: 'card' })
          append(card,
            el('h3', { text: booking.venueName || 'Заведение' }),
            el('p', { text: booking.displayLabel || 'Бронь' }),
            el('p', { className: 'status', text: [booking.scheduledAtDisplay || formatDateTime(booking.scheduledAt), booking.statusLabel].filter(Boolean).join(' · ') })
          )
          list.appendChild(card)
        }
        cursor = result.data.items.at(-1)?.bookingId ?? cursor
        finish(result.data.items.length, 'Здесь появятся завершённые и отменённые брони.')
        return
      }
    } else {
      const result = await guestGetVisits(backendUrl, { limit: 20, cursor, withOrders: kind === 'orders' }, deps, controller.signal)
      if (controller.signal.aborted) return
      if (result.ok) {
        for (const item of result.data.items) {
          const summary = renderVisitSummary(item, (id) => { window.location.hash = historyHash(id) }, kind === 'orders')
          list.appendChild(summary.card)
        }
        cursor = result.data.items.at(-1)?.visitId ?? cursor
        finish(result.data.items.length, kind === 'orders' ? 'Завершённых заказов пока нет.' : 'Состоявшихся посещений пока нет.')
        return
      }
    }
    loading = false
    status.textContent = visitId ? 'Не удалось загрузить детали истории.' : 'Не удалось загрузить историю.'
    more.textContent = 'Повторить'
    more.hidden = false
  }
  const finish = (count: number, empty: string) => {
    loading = false
    status.textContent = list.childElementCount === 0 ? empty : ''
    more.textContent = 'Показать ещё'
    more.hidden = count < 20
  }
  more.addEventListener('click', () => void load())
  void load()
  return () => { controller.abort(); detailDispose?.() }
}

function renderFavoriteVenue(
  item: GuestFavoriteVenueDto,
  pending: boolean,
  onOpenVenue: (venueId: number) => void,
  onBookVenue: (venueId: number) => void,
  onAskVenue: (venueId: number) => void,
  onRemove: (venueId: number) => void
) {
  const card = el('article', { className: 'card' })
  const title = el('h3', { text: item.name })
  const meta = el('p', {
    className: 'venue-order-sub',
    text: [item.city, item.address].filter((value): value is string => Boolean(value)).join(' · ') || 'Любимое заведение'
  })
  const actions = el('div', { className: 'venue-inline-actions' })
  const openButton = el('button', { className: 'button-secondary', text: 'Открыть заведение' }) as HTMLButtonElement
  const bookButton = el('button', { className: 'button-secondary', text: 'Забронировать' }) as HTMLButtonElement
  const askButton = el('button', { className: 'button-secondary', text: 'Задать вопрос' }) as HTMLButtonElement
  const removeButton = el('button', {
    className: 'button-danger',
    text: pending ? 'Удаляем...' : 'Удалить из избранного'
  }) as HTMLButtonElement
  removeButton.disabled = pending
  append(actions, openButton, bookButton, askButton, removeButton)
  append(card, title, meta, actions)
  return {
    card,
    dispose: () => {
      const disposables = [
        on(openButton, 'click', () => onOpenVenue(item.venueId)),
        on(bookButton, 'click', () => onBookVenue(item.venueId)),
        on(askButton, 'click', () => onAskVenue(item.venueId)),
        on(removeButton, 'click', () => onRemove(item.venueId))
      ]
      return () => disposables.forEach((dispose) => dispose())
    }
  }
}

function renderFavoritesSection(
  root: HTMLElement,
  backendUrl: string,
  isDebug: boolean,
  onBack: () => void,
  onOpenVenue: (venueId: number) => void,
  onBookVenue: (venueId: number) => void,
  onAskVenue: (venueId: number) => void
) {
  const controller = new AbortController()
  const deps = requestDeps(isDebug)
  let cardDisposables: Array<() => void> = []
  let disposed = false
  let venues: GuestFavoriteVenueDto[] = []
  let cursor: string | null = null
  let loading = false
  const pendingVenueIds = new Set<number>()
  const mutationControllers = new Set<AbortController>()
  const wrapper = el('div', { className: 'venue-settings' })
  const list = el('div', { className: 'favorite-venue-list' })
  const message = el('p', { className: 'status', text: '' })

  const renderList = () => {
    cardDisposables.forEach((dispose) => dispose())
    cardDisposables = []
    list.replaceChildren()
    if (venues.length === 0) {
      append(
        list,
        el('section', {
          className: 'card',
          text: 'Пока нет избранных заведений. Добавляйте их из каталога или карточки заведения.'
        })
      )
      return
    }
    venues.forEach((venue) => {
      const rendered = renderFavoriteVenue(
        venue,
        pendingVenueIds.has(venue.venueId),
        onOpenVenue,
        onBookVenue,
        onAskVenue,
        (venueId) => void removeFavorite(venueId)
      )
      append(list, rendered.card)
      cardDisposables.push(rendered.dispose())
    })
  }

  const removeFavorite = async (venueId: number) => {
    if (disposed || pendingVenueIds.has(venueId)) return
    pendingVenueIds.add(venueId)
    message.textContent = ''
    renderList()
    const mutationController = new AbortController()
    mutationControllers.add(mutationController)
    const result = await guestRemoveFavoriteVenue(backendUrl, venueId, deps, mutationController.signal)
    mutationControllers.delete(mutationController)
    if (disposed || (!result.ok && result.error.code === REQUEST_ABORTED_CODE)) return
    pendingVenueIds.delete(venueId)
    if (result.ok) {
      venues = venues.filter((venue) => venue.venueId !== venueId)
    } else {
      message.textContent = 'Не удалось изменить избранное.'
    }
    renderList()
  }

  const header = el('section', { className: 'card' })
  const backButton = el('button', { text: 'К профилю', className: 'button-secondary button-small' })
  const more = el('button', { text: 'Показать ещё', className: 'button-secondary button-small' })
  message.setAttribute('role', 'status')
  append(header, backButton, el('h1', { text: 'Избранное' }))
  append(wrapper, header, message, list, more)
  root.replaceChildren(wrapper)
  backButton.addEventListener('click', onBack)
  const loadPage = async () => {
    if (loading) return
    loading = true
    more.hidden = true
    message.textContent = 'Загружаем избранное…'
    const result = await guestGetFavoriteVenues(backendUrl, deps, controller.signal, cursor)
    if (disposed) return
    loading = false
    if (!result.ok) {
      message.textContent = 'Не удалось загрузить избранное.'
      more.textContent = 'Повторить'
      more.hidden = false
      return
    }
    message.textContent = ''
    const existing = new Set(venues.map((venue) => venue.venueId))
    venues = [...venues, ...result.data.venues.filter((venue) => !existing.has(venue.venueId))]
    cursor = result.data.nextCursor ?? null
    more.textContent = 'Показать ещё'
    more.hidden = cursor === null
    renderList()
  }
  more.addEventListener('click', () => void loadPage())
  void loadPage()

  return () => {
    disposed = true
    controller.abort()
    mutationControllers.forEach((mutationController) => mutationController.abort())
    cardDisposables.forEach((dispose) => dispose())
  }
}

export function renderGuestAccountScreen(options: GuestAccountScreenOptions) {
  const {
    root, backendUrl, isDebug, profileDraft, onOpenHelp, tableContext, onBack, onOpenBookings,
    onOpenVenue, onBookVenue, onAskVenue, onNavigateCart, selectedVenueId
  } = options
  if (!root) return () => undefined

  const section = (window.location.hash.split('/')[2]?.split('?')[0] ?? 'home') as AccountSection
  switch (section) {
    case 'profile':
      return renderGuestProfile(root, backendUrl, isDebug, profileDraft, onBack)
    case 'history':
      return renderHistorySection(root, backendUrl, isDebug, tableContext, onNavigateCart, onBack)
    case 'favorites':
      return renderFavoritesSection(root, backendUrl, isDebug, onBack, onOpenVenue, onBookVenue, onAskVenue)
    case 'loyalty':
      return renderGuestLoyalty(root, backendUrl, isDebug, selectedVenueId, tableContext, onBack, onOpenVenue)
    default:
      return renderAccountHome(root, onOpenBookings, onOpenHelp)
  }
}
