import { clearSession, getAccessToken } from '../shared/api/auth'
import { requestApi } from '../shared/api/request'
import type { TableContextSnapshot } from '../shared/state/tableContext'
import { append, el } from '../shared/ui/dom'

type LoyaltyTarget = { type: string; name: string | null }
type LoyaltyProgram = {
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
    const paidRequired = program.nthValue - 1
    const activeHere = table.orderAllowed && table.venueId === program.venueId
    const earn = targetText(program.earnTargets)
    const reward = targetText(program.rewardTargets)
    append(card,
      el('h2', { text: program.venueName }),
      el('p', { text: `Бесплатный кальян после ${paidRequired} оплаченных по программе.` }),
      el('p', { text: `Оплачено по программе · ${program.progressCount} из ${paidRequired}` }),
      el('p', { text: `Доступно наград · ${program.rewardsAvailable}` }),
      el('p', { text: `Зарезервировано в заказах · ${program.rewardsReserved}` })
    )
    if (earn) card.appendChild(el('p', { className: 'status', text: `Участвуют в накоплении · ${earn}` }))
    if (reward) card.appendChild(el('p', { className: 'status', text: `Можно выбрать в награду · ${reward}` }))
    if (program.rewardsAvailable > 0) {
      card.appendChild(el('p', { className: 'status', text: activeHere
        ? 'Добавьте подходящий кальян в корзину. Доступная награда появится в итогах перед отправкой заказа.'
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
    retry.hidden = true
    status.textContent = 'Загружаем лояльность…'
    const fetchPrograms = (suffix: string) =>
      requestApi<{ items: LoyaltyProgram[] }>(backendUrl, `/api/guest/loyalty${suffix}`, { signal: controller.signal }, deps)
    const [all, selected] = await Promise.all([fetchPrograms(''), venueId ? fetchPrograms(`?venueId=${venueId}`) : null])
    if (controller.signal.aborted) return
    if (!all.ok || (selected && !selected.ok && selected.error.status !== 404)) {
      status.textContent = 'Не удалось загрузить программы лояльности.'
      retry.hidden = false
      return
    }
    const programs = new Map(all.data.items.map((program) => [program.venueId, program]))
    if (selected?.ok) for (const program of selected.data.items) programs.set(program.venueId, program)
    const cards = [...programs.values()].filter((program) => program.programType === 'NTH_HOOKAH_FREE').map(renderProgram)
    status.textContent = cards.length ? '' : 'Здесь появится прогресс после заказов в заведениях с программой лояльности.'
    list.replaceChildren(...cards)
  }
  retry.addEventListener('click', () => void load())
  void load()
  return () => controller.abort()
}
