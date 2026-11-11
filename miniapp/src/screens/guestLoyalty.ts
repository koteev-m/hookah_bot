import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import type { TableContextSnapshot } from '../shared/state/tableContext'
import { append, el } from '../shared/ui/dom'

type LoyaltyTarget = { type: string; name: string | null }
type LoyaltyProgram = {
  programId?: number
  status?: string
  currentSessionRewardUsed?: boolean
  cycles?: { nthValue: number; progressCount: number; earnTargets: string[]; rewardTargets?: string[]; origin: string }[]
  rewardConditions?: { nthValue: number; count: number; rewardTargets: string[]; origin: string }[]
  rewardsApplied?: number
  legacyProgress?: number
  legacyAvailable?: number
  legacyUnresolvedProgress?: number
  venueId: number
  venueName: string
  programType: string
  nthValue: number
  progressCount: number
  rewardsAvailable: number
  rewardsReserved: number
  earnTargets: LoyaltyTarget[]
  rewardTargets: LoyaltyTarget[]
}

function targetText(targets: LoyaltyTarget[]) {
  if (targets.some((target) => target.type === 'CATEGORY_TYPE')) return 'Кальяны из меню'
  return targets.flatMap((target) => target.name ? [target.name] : []).join(', ')
}

export function renderGuestLoyalty(
  root: HTMLElement, backendUrl: string, isDebug: boolean, venueId: number | null, table: TableContextSnapshot,
  onBack: () => void, onOpenVenue: (venueId: number) => void
) {
  const controller = new AbortController()
  const deps = { isDebug, getAccessToken, clearSession }
  const wrapper = el('div', { className: 'venue-settings' })
  const header = el('section', { className: 'card' })
  const back = el('button', { text: 'К профилю', className: 'button-secondary button-small' })
  back.addEventListener('click', onBack)
  const status = el('p', { className: 'status' })
  status.setAttribute('role', 'status')
  const retry = el('button', { text: 'Повторить', className: 'button-secondary button-small' })
  const list = el('div', { className: 'venue-settings' })
  append(header, back, el('h1', { text: 'Лояльность' }))
  append(wrapper, header, status, retry, list)
  root.replaceChildren(wrapper)

  const renderProgram = (program: LoyaltyProgram) => {
    const card = el('section', { className: 'card guest-loyalty-program' })
    const cycle = program.cycles?.[0]
    const paidRequired = (cycle?.nthValue ?? program.nthValue) - 1
    const currentProgress = cycle?.progressCount ?? program.progressCount
    const enabled = !program.status || program.status === 'ACTIVE'
    const activeHere = enabled && table.orderAllowed && table.venueId === program.venueId
    const earn = cycle?.earnTargets.join(', ') ?? targetText(program.earnTargets)
    const reward = program.rewardConditions?.length
      ? [...new Set(program.rewardConditions.flatMap(condition => condition.rewardTargets))].join(', ')
      : cycle?.rewardTargets?.join(', ') ?? targetText(program.rewardTargets)
    append(card,
      el('h2', { text: program.venueName }),
      el('p', { text: `Бесплатный кальян после ${paidRequired} подтверждённо оплаченных кальянов.` }),
      el('p', { text: `Прогресс программы · ${currentProgress} из ${paidRequired}` }),
      el('p', { className: 'status', text: 'Прогресс начисляется после выдачи кальянов, закрытия заказа и полной подтверждённой оплаты соответствующего счёта. Частичная оплата не начисляет прогресс.' }),
      el('p', { className: 'status', text: 'Если заказ общий, дождитесь оплаты всего заказа, включая другие вкладки.' }),
      el('p', { className: 'status', text: 'Бесплатная единица по награде не участвует в новом накоплении. Уже сохранённая в заказе скидка не создаёт долг гостя.' }),
      el('p', { text: `Доступно наград · ${program.rewardsAvailable}` }),
      el('p', { text: `Зарезервировано в заказах · ${program.rewardsReserved}` }),
      el('p', { text: `Выдано наград · ${program.rewardsApplied ?? 0}` })
    )
    if (!enabled) card.appendChild(el('p', { className: 'status', text: 'Программа приостановлена или архивирована. Накопления сохранены; использование сейчас недоступно.' }))
    if ((program.legacyProgress ?? 0) > 0 || (program.legacyAvailable ?? 0) > 0 || program.rewardConditions?.some(condition => condition.origin === 'LEGACY_CUTOVER')) card.appendChild(el('p', { className: 'status', text: 'Сохранены исторические остатки. Их происхождение не является подтверждением оплаты.' }))
    if ((program.legacyUnresolvedProgress ?? 0) > 0) card.appendChild(el('p', { className: 'status', text: `Исторический прогресс сохранён отдельно: ${program.legacyUnresolvedProgress}. Условия требуют проверки заведением; новые оплаченные покупки учитываются в новом цикле.` }))
    if ((program.cycles?.length ?? 0) > 1) for (const cycle of program.cycles ?? []) card.appendChild(el('p', {
      text: `Цикл: ${cycle.progressCount} из ${cycle.nthValue - 1} · ${cycle.earnTargets.join(', ')}`
    }))
    for (const condition of program.rewardConditions ?? []) card.appendChild(el('p', {
      text: `Награды на сохранённых условиях: ${condition.count} · каждый ${condition.nthValue}-й · ${condition.rewardTargets.join(', ')}`
    }))
    if (earn) card.appendChild(el('p', { className: 'status', text: `Участвуют в накоплении · ${earn}` }))
    if (reward) card.appendChild(el('p', { className: 'status', text: `Можно выбрать в награду · ${reward}` }))
    if (program.currentSessionRewardUsed) card.appendChild(el('p', { className: 'status', text: 'Награда этой программы уже зарезервирована или использована в этом визите. Другие доступные награды сохранены до следующего посещения.' }))
    if (program.rewardsAvailable > 0 && enabled && !program.currentSessionRewardUsed) {
      card.appendChild(el('p', { className: 'status', text: 'При отправке заказа награда резервируется. После подтверждённой выдачи она использована. При отмене до выдачи статус обновится; повторное использование зависит от условий программы. После выдачи автоматического возврата нет.' }))
      card.appendChild(el('p', { className: 'status', text: activeHere
        ? 'Добавьте подходящий кальян в корзину. Сервер проверит награду, наличие и варианты. В одном визите используется одна награда программы.'
        : 'Чтобы использовать награду, отсканируйте QR стола в этом заведении и добавьте подходящий кальян.' }))
    }
    const open = el('button', { className: 'button-secondary', text: activeHere ? 'К меню' : 'Открыть заведение' })
    open.addEventListener('click', () => {
      if (activeHere) window.location.hash = `#/venue/${program.venueId}?browseMenu=1`
      else onOpenVenue(program.venueId)
    })
    card.appendChild(open)
    return card
  }

  const load = async () => {
    retry.disabled = true
    retry.hidden = true
    status.textContent = 'Загружаем лояльность…'
    const fetchPrograms = (suffix: string) =>
      requestApi<{ items: LoyaltyProgram[] }>(backendUrl, `/api/guest/loyalty${suffix}${table.tableSessionId ? `${suffix ? '&' : '?'}tableSessionId=${table.tableSessionId}` : ''}`, { signal: controller.signal }, deps)
    const [all, selected] = await Promise.all([fetchPrograms(''), venueId ? fetchPrograms(`?venueId=${venueId}`) : null])
    if (controller.signal.aborted) return
    if (!all.ok || (selected && !selected.ok && selected.error.status !== 404)) {
      status.textContent = 'Не удалось загрузить программы лояльности.'
      retry.textContent = 'Повторить'
      retry.hidden = false
      retry.disabled = false
      return
    }
    const programs = new Map(all.data.items.map((program) => [`${program.venueId}:${program.programId ?? "current"}`, program]))
    if (selected?.ok) for (const program of selected.data.items) programs.set(`${program.venueId}:${program.programId ?? "current"}`, program)
    const cards = [...programs.values()].filter((program) => program.programType === 'NTH_HOOKAH_FREE').map(renderProgram)
    status.textContent = cards.length ? '' : 'Здесь появится прогресс после заказов в заведениях с программой лояльности.'
    list.replaceChildren(...cards)
    retry.textContent = 'Обновить'
    retry.hidden = false
    retry.disabled = false
  }
  retry.addEventListener('click', () => void load())
  void load()
  return () => controller.abort()
}
