import { append, el, on } from '../shared/ui/dom'

export type PlatformCockpitSection = 'onboarding' | 'placements' | 'support' | 'analytics'

export type PlatformCockpitSectionOptions = {
  root: HTMLDivElement | null
  section: PlatformCockpitSection
  onNavigate: (hash: string) => void
}

type SectionCopy = {
  title: string
  lead: string
  points: string[]
}

const sectionCopy: Record<PlatformCockpitSection, SectionCopy> = {
  onboarding: {
    title: 'Подключение заведений',
    lead:
      'В Mini App уже доступны создание заведения, назначение owner и owner invite. Заявки на подключение пока остаются в Telegram-боте.',
    points: [
      'Создайте заведение в разделе «Заведения».',
      'Назначьте владельца или сгенерируйте owner invite в карточке заведения.',
      'Если заявка пришла через бот, обработайте её в текущем platform owner flow.'
    ]
  },
  placements: {
    title: 'Размещения',
    lead:
      'Баннеры, афиши и «Топ в Акциях» пока управляются в Telegram-боте. В Mini App нет частично работающих controls для размещений.',
    points: [
      'Проверяйте pending/active/archive размещения в боте.',
      'Одобрение сроков и контакт с заявителем остаются в platform owner flow.',
      'Mini App не меняет статусы размещений в этом релизе.'
    ]
  },
  support: {
    title: 'Поддержка',
    lead:
      'Автоматическая система тикетов будет добавлена позже. Сейчас обращения обрабатываются вручную через Telegram-бот или операционный контакт платформы.',
    points: [
      'В Mini App нет номеров тикетов, статусов или очереди обращений.',
      'По обращениям заведений используйте действующий Telegram flow.',
      'Деньги, доступы и роли не меняются из этой секции.'
    ]
  },
  analytics: {
    title: 'Аналитика',
    lead:
      'Платформенная аналитика в Mini App появится отдельным блоком. Сейчас здесь не показываются тестовые или приблизительные числа.',
    points: [
      'Для операционных данных используйте карточки заведений и существующие bot reports.',
      'Launch baseline не должен содержать fake analytics.',
      'Расширенный dashboard вынесен в следующий product block.'
    ]
  }
}

export function renderPlatformCockpitSectionScreen(options: PlatformCockpitSectionOptions) {
  const { root, section, onNavigate } = options
  if (!root) return () => undefined
  const copy = sectionCopy[section]
  const wrapper = el('div', { className: 'venue-settings' })
  const card = el('div', { className: 'card' })
  const list = el('ul', { className: 'platform-readiness-list' })
  copy.points.forEach((point) => {
    list.appendChild(el('li', { text: point }))
  })

  const actions = el('div', { className: 'venue-inline-actions' })
  const backButton = el('button', { text: 'К заведениям' }) as HTMLButtonElement
  append(actions, backButton)
  append(card, el('h2', { text: copy.title }), el('p', { text: copy.lead }), list, actions)
  append(wrapper, card)
  root.replaceChildren(wrapper)

  const disposeBack = on(backButton, 'click', () => onNavigate('#/venues'))
  return () => {
    disposeBack()
  }
}
