import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  guestGetFavoriteVenues,
  guestRemoveFavoriteVenue,
  guestGetVisitDetail,
  guestGetVisits,
  guestSubmitVisitFeedback
} from '../shared/api/guestApi'
import type {
  GuestFavoriteVenueDto,
  GuestVisitFeedbackDto,
  GuestVisitDetailDto,
  GuestVisitListItemDto,
  GuestVisitOrderDto
} from '../shared/api/guestDtos'
import type { ApiErrorInfo } from '../shared/api/types'
import { append, el, on } from '../shared/ui/dom'
import { formatPrice } from '../shared/ui/price'

type AccountSection = 'home' | 'profile' | 'history' | 'favorites' | 'promotions' | 'loyalty'
type OpenBotResult = { ok: true } | { ok: false; message: string }

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
  hasTableContext: boolean
  onBack: () => void
  onOpenBookings: () => void
  onOpenVenue: (venueId: number) => void
  onBookVenue: (venueId: number) => void
  onAskVenue: (venueId: number) => void
  onOpenBot: () => OpenBotResult
  onInternalBackStateChange?: (handler: (() => void) | null) => void
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

function renderLoading(root: HTMLElement, title: string): HTMLParagraphElement {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: title })
  const message = el('p', { className: 'venue-order-sub', text: 'Загружаем данные...' })
  append(section, heading, message)
  root.replaceChildren(section)
  return message
}

function renderErrorSection(root: HTMLElement, title: string, body: string, onBack: () => void) {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: title })
  const text = el('p', { text: body })
  const backButton = el('button', { text: 'К профилю' }) as HTMLButtonElement
  append(section, heading, text, backButton)
  root.replaceChildren(section)
  const dispose = on(backButton, 'click', onBack)
  return () => dispose()
}

function renderHistoryDetailError(root: HTMLElement, onBack: () => void) {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: 'История' })
  const text = el('p', { text: 'Не удалось загрузить детали истории.' })
  const backButton = el('button', { text: '← Назад к истории' }) as HTMLButtonElement
  append(section, heading, text, backButton)
  root.replaceChildren(section)
  const dispose = on(backButton, 'click', onBack)
  return () => dispose()
}

function renderBotOnlySection(
  root: HTMLElement,
  title: string,
  body: string,
  onBack: () => void,
  onOpenBot: () => OpenBotResult
) {
  const section = el('section', { className: 'card' })
  const heading = el('h2', { text: title })
  const text = el('p', { text: body })
  const hint = el('p', {
    className: 'venue-order-sub',
    text: 'Mini App не показывает здесь фейковые данные и не обещает недоступные действия.'
  })
  const message = el('p', { className: 'staff-message', text: '' })
  message.hidden = true
  const actions = el('div', { className: 'venue-inline-actions' })
  const botButton = el('button', { className: 'button-secondary', text: 'Открыть чат с ботом' }) as HTMLButtonElement
  const backButton = el('button', { text: 'К профилю' }) as HTMLButtonElement
  append(actions, botButton, backButton)
  append(section, heading, text, hint, message, actions)
  root.replaceChildren(section)

  const disposables = [
    on(botButton, 'click', () => {
      const result = onOpenBot()
      message.hidden = false
      message.textContent = result.ok ? 'Открываем чат с ботом.' : result.message
    }),
    on(backButton, 'click', onBack)
  ]
  return () => disposables.forEach((dispose) => dispose())
}

function renderAccountHome(
  root: HTMLElement,
  openSection: (section: AccountSection) => void,
  onBack: () => void,
  onOpenBookings: () => void
) {
  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('section', { className: 'card' })
  const heading = el('h2', { text: 'Профиль' })
  const body = el('p', {
    text: 'Здесь собраны гостевые разделы: брони, история, избранные заведения, акции и лояльность.'
  })
  const actions = el('div', { className: 'venue-inline-actions' })
  const buttons: Array<[AccountSection, string]> = [
    ['profile', '👤 Профиль'],
    ['history', '🕘 История'],
    ['favorites', '⭐ Избранные заведения'],
    ['promotions', '🎁 Акции'],
    ['loyalty', '🎁 Лояльность']
  ]
  const disposables: Array<() => void> = []
  const bookingsButton = el('button', { className: 'button-secondary', text: '📅 Мои брони' }) as HTMLButtonElement
  append(actions, bookingsButton)
  disposables.push(on(bookingsButton, 'click', onOpenBookings))
  buttons.forEach(([section, label]) => {
    const button = el('button', { className: 'button-secondary', text: label }) as HTMLButtonElement
    append(actions, button)
    disposables.push(on(button, 'click', () => openSection(section)))
  })
  const backButton = el('button', { text: 'Назад' }) as HTMLButtonElement
  append(actions, backButton)
  disposables.push(on(backButton, 'click', onBack))
  append(card, heading, body, actions)
  append(wrapper, card)
  root.replaceChildren(wrapper)
  return () => disposables.forEach((dispose) => dispose())
}

function renderVisitSummary(item: GuestVisitListItemDto, onOpen: (visitId: number) => void) {
  const card = el('article', { className: 'card' })
  const title = el('h3', { text: item.venueName })
  const date = el('p', { className: 'venue-order-sub', text: formatDateTime(item.occurredAt) })
  const total = formatMoney(item.totalMinor, item.currency)
  const details = [
    item.venueCity ? `Город: ${item.venueCity}` : null,
    total ? `Итого: ${total}` : null,
    item.orderLabels.length > 0 ? `Заказы: ${item.orderLabels.join(', ')}` : null,
    item.hasBooking ? 'Было бронирование' : null
  ].filter((value): value is string => Boolean(value))
  const meta = el('p', { className: 'venue-order-sub', text: details.length > 0 ? details.join(' · ') : 'Детали визита доступны внутри.' })
  const openButton = el('button', { className: 'button-secondary', text: 'Подробнее' }) as HTMLButtonElement
  append(card, title, date, meta, openButton)
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
      itemName,
      item.selectedOption?.name ?? null,
      item.preferenceNote ? `Пожелание: ${item.preferenceNote}` : null
    ].filter((value): value is string => Boolean(value))
    const line = el('p', {
      className: 'venue-order-sub',
      text: `${itemDetails.join(' · ')} ×${qty}${
        formatMoney(item.totalMinor, item.currency) ? ` — ${formatMoney(item.totalMinor, item.currency)}` : ''
      }`
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
        text: `${discount.label}: −${formatPrice(discount.discountMinor, discount.currency)}`
      })
    )
  })
  append(card, title, meta, itemList, discountList)
  return card
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
  onBack: () => void
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
  if (!feedbackBlock.hidden) {
    append(wrapper, feedbackBlock.element)
  }
  visit.orders.forEach((order) => append(wrapper, renderVisitOrder(order)))
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
  }
}

function renderHistorySection(
  root: HTMLElement,
  backendUrl: string,
  isDebug: boolean,
  onBack: () => void,
  onInternalBackStateChange?: (handler: (() => void) | null) => void
) {
  const controller = new AbortController()
  let nestedDispose: (() => void) | null = null
  renderLoading(root, 'История')
  const deps = requestDeps(isDebug)

  const loadList = async () => {
    onInternalBackStateChange?.(onBack)
    nestedDispose?.()
    nestedDispose = null
    const result = await guestGetVisits(backendUrl, { limit: 20 }, deps, controller.signal)
    if (!result.ok) {
      if (result.error.code === REQUEST_ABORTED_CODE) return
      nestedDispose = renderErrorSection(root, 'История', errorText(result.error), onBack)
      return
    }
    const wrapper = el('div', { className: 'venue-settings' })
    const header = el('section', { className: 'card' })
    const heading = el('h2', { text: 'История' })
    const body = el('p', {
      className: 'venue-order-sub',
      text: 'Визиты и итоговые суммы приходят из backend-истории. Mini App не пересчитывает деньги.'
    })
    const backButton = el('button', { text: 'К профилю' }) as HTMLButtonElement
    append(header, heading, body, backButton)
    append(wrapper, header)
    const disposables: Array<() => void> = [on(backButton, 'click', onBack)]
    if (result.data.items.length === 0) {
      append(wrapper, el('section', { className: 'card', text: 'История пока пустая.' }))
    }
    result.data.items.forEach((item) => {
      const rendered = renderVisitSummary(item, (visitId) => {
        void loadDetail(visitId)
      })
      append(wrapper, rendered.card)
      disposables.push(rendered.dispose)
    })
    root.replaceChildren(wrapper)
    nestedDispose = () => disposables.forEach((dispose) => dispose())
  }

  const loadDetail = async (visitId: number) => {
    onInternalBackStateChange?.(() => {
      void loadList()
    })
    nestedDispose?.()
    nestedDispose = null
    renderLoading(root, 'История')
    try {
      const result = await guestGetVisitDetail(backendUrl, visitId, deps, controller.signal)
      if (!result.ok) {
        if (result.error.code === REQUEST_ABORTED_CODE) return
        nestedDispose = renderHistoryDetailError(root, () => {
          void loadList()
        })
        return
      }
      nestedDispose = renderVisitDetail(root, result.data.visit, backendUrl, isDebug, controller.signal, () => {
        void loadList()
      })
    } catch {
      nestedDispose = renderHistoryDetailError(root, () => {
        void loadList()
      })
    }
  }

  void loadList()

  return () => {
    controller.abort()
    nestedDispose?.()
    onInternalBackStateChange?.(null)
  }
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
  renderLoading(root, 'Избранные заведения')
  const deps = requestDeps(isDebug)
  let staticDisposables: Array<() => void> = []
  let cardDisposables: Array<() => void> = []
  let errorDispose: (() => void) | null = null
  let disposed = false
  let venues: GuestFavoriteVenueDto[] = []
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

  void guestGetFavoriteVenues(backendUrl, deps, controller.signal).then((venuesResult) => {
    if (disposed) return
    if (!venuesResult.ok) {
      if (venuesResult.error.code === REQUEST_ABORTED_CODE) return
      errorDispose = renderErrorSection(root, 'Избранные заведения', errorText(venuesResult.error), onBack)
      return
    }

    const header = el('section', { className: 'card' })
    const heading = el('h2', { text: 'Избранные заведения' })
    const body = el('p', {
      className: 'venue-order-sub',
      text: 'Список синхронизирован с избранным в Telegram-боте.'
    })
    const backButton = el('button', { text: 'К профилю' }) as HTMLButtonElement
    append(header, heading, body, backButton)
    append(wrapper, header, message, list)
    staticDisposables = [on(backButton, 'click', onBack)]
    venues = venuesResult.data.venues
    renderList()
    root.replaceChildren(wrapper)
  })

  return () => {
    disposed = true
    controller.abort()
    mutationControllers.forEach((mutationController) => mutationController.abort())
    errorDispose?.()
    cardDisposables.forEach((dispose) => dispose())
    staticDisposables.forEach((dispose) => dispose())
  }
}

export function renderGuestAccountScreen(options: GuestAccountScreenOptions) {
  const {
    root,
    backendUrl,
    isDebug,
    hasTableContext,
    onBack,
    onOpenBookings,
    onOpenVenue,
    onBookVenue,
    onAskVenue,
    onOpenBot,
    onInternalBackStateChange
  } = options
  if (!root) return () => undefined

  let currentDispose: (() => void) | null = null
  const renderSection = (section: AccountSection) => {
    currentDispose?.()
    currentDispose = null
    const goHome = () => renderSection('home')
    switch (section) {
      case 'profile':
        onInternalBackStateChange?.(goHome)
        currentDispose = renderBotOnlySection(
          root,
          'Профиль',
          'Редактирование имени, контактов и других персональных данных пока доступно в Telegram-боте.',
          goHome,
          onOpenBot
        )
        break
      case 'history':
        currentDispose = renderHistorySection(root, backendUrl, isDebug, goHome, onInternalBackStateChange)
        break
      case 'favorites':
        onInternalBackStateChange?.(goHome)
        currentDispose = renderFavoritesSection(
          root,
          backendUrl,
          isDebug,
          goHome,
          onOpenVenue,
          onBookVenue,
          onAskVenue
        )
        break
      case 'promotions':
        onInternalBackStateChange?.(goHome)
        currentDispose = renderBotOnlySection(
          root,
          'Акции',
          hasTableContext
            ? 'Акции текущего заведения пока открываются в Telegram-боте. В корзине Mini App применимые скидки учитываются автоматически.'
            : 'Акции заведений пока открываются в Telegram-боте и в карточках заведений.',
          goHome,
          onOpenBot
        )
        break
      case 'loyalty':
        onInternalBackStateChange?.(goHome)
        currentDispose = renderBotOnlySection(
          root,
          'Лояльность',
          'Прогресс по лояльности пока доступен в профиле Telegram-бота. Mini App не показывает здесь неполные данные.',
          goHome,
          onOpenBot
        )
        break
      case 'home':
      default:
        onInternalBackStateChange?.(null)
        currentDispose = renderAccountHome(root, renderSection, onBack, onOpenBookings)
        break
    }
  }

  renderSection('home')

  return () => {
    currentDispose?.()
    onInternalBackStateChange?.(null)
  }
}
