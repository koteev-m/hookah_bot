import { createStaffPhotoPanel, type StaffPhotoPanel, type StaffPhotoNavigation } from './staffPhoto'
import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateInvite,
  venueCreateStaffProfile,
  venueCreateStaffProfileFromMember,
  venueGetPendingStaffInvites,
  venueGetGuestPreview,
  venueGetStaff,
  venueGetStaffModuleSettings,
  venueGetStaffProfiles,
  venueHideStaffProfile,
  venuePublishStaffProfile,
  venueRemoveStaff,
  venueRevokeStaffInvite,
  venueReissueStaffInvite,
  venueUpdateRole,
  venueUpdateStaffProfile,
  venueUpsertTodayStaffShift
} from '../shared/api/venueApi'
import type {
  VenueAccessDto,
  VenueStaffMemberDto,
  VenueStaffInviteResponse,
  VenueStaffProfileDto,
  VenueStaffPendingInviteDto,
  VenueStaffProfileSubtype,
  VenueStaffProfileUpdateRequest,
  VenueStaffShiftStatus,
  VenueStaffModuleSettingsDto
} from '../shared/api/venueDtos'
import { ApiErrorCodes, type ApiErrorInfo } from '../shared/api/types'
import { getTelegramContext } from '../shared/telegram'
import { append, el, on } from '../shared/ui/dom'
import { presentApiError, type ApiErrorAction } from '../shared/ui/apiErrorPresenter'
import { renderErrorDetails } from '../shared/ui/errorDetails'
import { showToast } from '../shared/ui/toast'

export type VenueStaffOptions = {
  root: HTMLDivElement | null
  backendUrl: string
  isDebug: boolean
  venueId: number
  access: VenueAccessDto
  currentUserId: number
  onOpenStaffModuleSettings?: () => void
  onPhotoChange?: () => void
  setPhotoNavigation?: (navigation: StaffPhotoNavigation) => void
}

type StaffRefs = {
  accessCard: HTMLElement
  accessTab: HTMLButtonElement
  profilesTab: HTMLButtonElement
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  inviteRole: HTMLSelectElement
  inviteButton: HTMLButtonElement
  refreshButton: HTMLButtonElement
  pendingInviteStatus: HTMLParagraphElement
  pendingInviteList: HTMLDivElement
  list: HTMLDivElement
  profileCard: HTMLElement
  profileEnabledContent: HTMLDivElement
  profileDisabledPanel: HTMLDivElement
  profileEnableButton: HTMLButtonElement
  profileAddButton: HTMLButtonElement
  profileForm: HTMLDivElement
  profileNameField: HTMLElement
  profileName: HTMLInputElement
  profileSubtype: HTMLSelectElement
  profileRoleLabelField: HTMLElement
  profileRoleLabel: HTMLInputElement
  profileLinkedUserField: HTMLElement
  profileLinkedUser: HTMLSelectElement
  profilePhotoPlaceholder: HTMLElement
  profileBioField: HTMLElement
  profileBio: HTMLTextAreaElement
  profileTagsField: HTMLElement
  profileTags: HTMLInputElement
  profileCreateButton: HTMLButtonElement
  profileCancelButton: HTMLButtonElement
  profileStatus: HTMLParagraphElement
  profileList: HTMLDivElement
}

function buildApiDeps(isDebug: boolean) {
  return { isDebug, getAccessToken, clearSession }
}

let profileFieldId = 0

function renderProfileField<T extends HTMLElement>(
  labelText: string,
  control: T,
  helpText?: string
) {
  const field = el('div', { className: 'venue-profile-field' })
  const label = el('label', { className: 'field-label', text: labelText }) as HTMLLabelElement
  control.id = control.id || `venue-profile-field-${profileFieldId++}`
  label.htmlFor = control.id
  field.appendChild(label)
  field.appendChild(control)
  if (helpText) {
    field.appendChild(el('p', { className: 'field-help', text: helpText }))
  }
  return field
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

function buildStaffDom(root: HTMLDivElement, access: VenueAccessDto): StaffRefs {
  const wrapper = el('div', { className: 'venue-staff' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Персонал' })
  const accessTitle = el('h3', { text: 'Аккаунты и доступ' })
  const accessDescription = el('p', {
    className: 'venue-order-sub',
    text: 'Telegram-аккаунты с доступом в кабинет и приглашения в это заведение.'
  })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteRole = document.createElement('select')
  inviteRole.className = 'venue-select'
  inviteRole.setAttribute('aria-label', 'Роль приглашения')
  inviteRole.appendChild(new Option('Сотрудник', 'STAFF'))
  if (access.role === 'OWNER') {
    inviteRole.appendChild(new Option('Менеджер', 'MANAGER'))
  }
  const inviteButton = el('button', { text: 'Создать приглашение' }) as HTMLButtonElement
  const refreshButton = el('button', { className: 'button-small button-secondary', text: 'Обновить доступы и карточки' }) as HTMLButtonElement
  append(inviteRow, inviteRole, inviteButton)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-staff-list' })

  const pendingInviteTitle = el('h3', { text: 'Приглашения' })
  const pendingInviteStatus = el('p', { className: 'status', text: '' }) as HTMLParagraphElement
  pendingInviteStatus.setAttribute('aria-live', 'polite')
  const pendingInviteList = el('div', { className: 'venue-staff-list venue-pending-invite-list' }) as HTMLDivElement

  append(
    header,
    accessTitle,
    accessDescription,
    el('p', { className: 'venue-staff-current-access', text: `Ваша роль · ${formatStaffRole(access.role)}` }),
    inviteRow,
    el('p', { className: 'field-help', text: 'Доступ выдаётся после принятия. Получатель до принятия неизвестен; Telegram не сообщает о доставке ссылки.' }),
    pendingInviteTitle,
    pendingInviteStatus,
    pendingInviteList,
    el('h3', { text: 'Аккаунты с доступом' }),
    list
  )

  const profileCard = el('section', { className: 'card venue-public-staff' })
  const profileTitle = el('h3', { text: 'Карточки команды' })
  const profileDisabledPanel = el('div', { className: 'venue-module-disabled' }) as HTMLDivElement
  const profileDisabledCopy = el('p', {
    className: 'venue-order-sub',
    text: 'Карточки команды и график смен отключены. Сохранённые данные не удалены, а доступ сотрудников к заказам и кабинету продолжает работать.'
  })
  const profileEnableButton = el('button', {
    className: 'button-small button-secondary',
    text: 'Включить в настройках'
  }) as HTMLButtonElement
  append(profileDisabledPanel, profileDisabledCopy, profileEnableButton)
  const profileEnabledContent = el('div', { className: 'venue-module-enabled' }) as HTMLDivElement
  const profileDescription = el('p', {
    className: 'venue-order-sub',
    text: 'Карточка описывает сотрудника и сама по себе не выдаёт доступ в кабинет. Её можно создать без Telegram-привязки.'
  })
  const privacyNote = el('p', { className: 'venue-order-sub', text: 'Гости видят только опубликованные карточки.' })
  const shiftNote = el('p', {
    className: 'venue-order-sub',
    text: 'В блок «Сегодня работают» попадают опубликованные карточки при включённом показе команды: по ручной отметке или активной смене, в зависимости от настроек.'
  })
  const protectedNote = el('p', {
    className: 'venue-order-sub',
    text: 'Карточками владельцев и других менеджеров управляет владелец. Их фотографии может менять и менеджер; права и привязки аккаунтов при этом не меняются.'
  })
  protectedNote.hidden = access.role !== 'MANAGER'
  const profileHelp = document.createElement('details')
  profileHelp.className = 'venue-profile-help'
  profileHelp.appendChild(el('summary', { text: 'Как работают карточки и показ гостям' }))
  append(profileHelp, privacyNote, shiftNote, protectedNote)
  const profileAddButton = el('button', { className: 'button-small', text: 'Добавить карточку сотрудника' }) as HTMLButtonElement
  const profileForm = el('div', { className: 'venue-profile-form' })
  profileForm.hidden = true
  const profileName = document.createElement('input')
  profileName.className = 'venue-input'
  profileName.placeholder = 'Например: Максим'
  profileName.maxLength = 120
  const profileNameField = renderProfileField('Имя на карточке', profileName, 'Так это имя увидят гости. Имя и привязка Telegram не изменятся.')
  const profileSubtype = document.createElement('select')
  profileSubtype.className = 'venue-select'
  profileSubtype.appendChild(new Option('Выберите тип сотрудника', ''))
  profileSubtype.appendChild(new Option('Кальянный мастер', 'hookah_master'))
  profileSubtype.appendChild(new Option('Официант', 'waiter'))
  profileSubtype.appendChild(new Option('Администратор', 'admin'))
  profileSubtype.appendChild(new Option('Другое', 'other'))
  const profileRoleLabel = document.createElement('input')
  profileRoleLabel.className = 'venue-input'
  profileRoleLabel.placeholder = 'Например: Бармен, Старший смены, Мастер миксов'
  profileRoleLabel.maxLength = 120
  const profileRoleLabelField = renderProfileField('Название роли', profileRoleLabel, 'Так роль будет показана гостям.')
  profileRoleLabelField.hidden = true
  const profileLinkedUser = document.createElement('select')
  profileLinkedUser.className = 'venue-select'
  const profileLinkedUserField = renderProfileField(
    'Привязать к сотруднику',
    profileLinkedUser,
    'Привязка нужна, чтобы сотрудник мог позже редактировать своё описание. Гостям эта связь не показывается.'
  )
  const profileBio = document.createElement('textarea')
  profileBio.className = 'venue-textarea'
  profileBio.placeholder = 'Например: Люблю крепкие миксы и помогаю подобрать вкус под настроение.'
  profileBio.maxLength = 1000
  profileBio.rows = 3
  const profileBioField = renderProfileField('Коротко о сотруднике', profileBio)
  const profileTags = document.createElement('input')
  profileTags.className = 'venue-input'
  profileTags.placeholder = 'Например: крепкие миксы, фруктовые чаши, авторские вкусы'
  const profileTagsField = renderProfileField(
    'Специализация',
    profileTags,
    'Можно указать через запятую. Это поможет гостям понять стиль сотрудника.'
  )
  const photoPlaceholder = el('div', {
    className: 'venue-profile-photo-placeholder',
    text: 'Фото можно добавить после создания карточки. После загрузки оно сразу станет текущим; видимость карточки не изменится.'
  })
  const profileCreateButton = el('button', { text: 'Создать профиль' }) as HTMLButtonElement
  const profileCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const profileCollapseButton = el('button', { className: 'button-secondary', text: 'Свернуть' }) as HTMLButtonElement
  profileCollapseButton.dataset.action = 'collapse-create'
  const profileFormActions = el('div', { className: 'venue-profile-form-actions' })
  append(profileFormActions, profileCreateButton, profileCollapseButton, profileCancelButton)
  append(
    profileForm,
    profileNameField,
    renderProfileField('Тип сотрудника', profileSubtype),
    profileRoleLabelField,
    profileLinkedUserField,
    photoPlaceholder,
    profileBioField,
    profileTagsField,
    profileFormActions
  )
  const profileStatus = el('p', { className: 'status', text: '' })
  const visibilityFreshness = el('p', { className: 'field-help', text: 'Видимость для гостей — на момент обновления.' })
  const profileList = el('div', { className: 'venue-staff-list venue-profile-list' })
  append(
    profileEnabledContent,
    profileDescription,
    profileHelp,
    profileAddButton,
    profileForm,
    profileStatus,
    visibilityFreshness,
    profileList
  )
  append(profileCard, profileTitle, profileDisabledPanel, profileEnabledContent)

  const navigation = el('nav', { className: 'venue-staff-tabs' })
  navigation.setAttribute('aria-label', 'Разделы персонала')
  const accessTab = el('button', { className: 'button-small', text: 'Аккаунты и доступ' }) as HTMLButtonElement
  const profilesTab = el('button', { className: 'button-small', text: 'Карточки команды' }) as HTMLButtonElement
  append(navigation, accessTab, profilesTab)
  if (access.role === 'MANAGER' && access.permissions.includes('STAFF_PROFILE_EDIT_OWN')) {
    const own = el('a', { className: 'venue-owner-action', text: 'Моя карточка' }) as HTMLAnchorElement
    own.href = '#/my-profile'
    navigation.appendChild(own)
  }
  append(wrapper, title, navigation, refreshButton, status, error, header, profileCard)
  root.replaceChildren(wrapper)

  return {
    accessCard: header, accessTab, profilesTab,
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    inviteRole,
    inviteButton,
    refreshButton,
    pendingInviteStatus,
    pendingInviteList,
    list,
    profileCard,
    profileEnabledContent,
    profileDisabledPanel,
    profileEnableButton,
    profileAddButton,
    profileForm,
    profileNameField,
    profileName,
    profileSubtype,
    profileRoleLabelField,
    profileRoleLabel,
    profileLinkedUserField,
    profileLinkedUser,
    profilePhotoPlaceholder: photoPlaceholder,
    profileBioField,
    profileBio,
    profileTagsField,
    profileTags,
    profileCreateButton,
    profileCancelButton,
    profileStatus,
    profileList
  }
}

function formatInviteExpires(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  return date.toLocaleString([], {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}

function formatStaffRole(role: string): string {
  switch (role.toUpperCase()) {
    case 'OWNER':
      return 'Владелец'
    case 'MANAGER':
      return 'Менеджер'
    case 'STAFF':
      return 'Сотрудник'
    default:
      return role
  }
}

function buildTelegramShareUrl(inviteLink: string, venueName: string, role: string): string {
  const text = `Приглашение в ${venueName}. Роль: ${role}. Откройте ссылку, чтобы принять доступ.`
  return `https://t.me/share/url?url=${encodeURIComponent(inviteLink)}&text=${encodeURIComponent(text)}`
}

function selectVisibleField(field: HTMLTextAreaElement) {
  field.focus()
  field.select()
  field.setSelectionRange(0, field.value.length)
}

function renderMemberRow(
  member: VenueStaffMemberDto,
  currentUserId: number,
  canManageRoles: boolean,
  canCreateProfiles: boolean,
  onUpdateRole: (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => void,
  onRemove: (member: VenueStaffMemberDto) => void,
  onCreateProfile: (member: VenueStaffMemberDto) => void,
  onOpenProfile: (member: VenueStaffMemberDto) => void
) {
  const row = el('div', { className: 'venue-staff-row' })
  row.dataset.memberId = String(member.userId)
  const info = el('div', { className: 'venue-staff-info' })
  const identityMeta = el('p', { className: 'venue-staff-identity-meta' })
  append(
    identityMeta,
    el('span', { text: formatMemberUsername(member) }),
    document.createTextNode(' · '),
    el('span', { className: 'venue-staff-role-badge', text: formatStaffRole(member.role) })
  )
  if (member.userId === currentUserId) {
    identityMeta.appendChild(el('span', { className: 'venue-staff-self', text: 'Это вы' }))
  }
  append(info, el('strong', { text: member.displayName }), identityMeta)

  const linkState = el('p', {
    className: 'venue-staff-link-state',
    text: formatMemberLinkState(member)
  })
  linkState.dataset.state = member.profileLinkState
  info.appendChild(linkState)
  if (member.profileLinkState === 'DUPLICATE_LINK_DETECTED') {
    info.appendChild(
      el('p', {
        className: 'venue-staff-link-warning',
        text: DUPLICATE_LINK_WARNING
      })
    )
  }

  const actions = el('div', { className: 'venue-staff-actions' })
  if (member.profileLinkState === 'NOT_LINKED' && canCreateProfiles && member.active) {
    const createButton = el('button', {
      className: 'button-small venue-staff-profile-link-action',
      text: 'Создать карточку'
    }) as HTMLButtonElement
    createButton.addEventListener('click', () => onCreateProfile(member))
    actions.appendChild(createButton)
  } else if (member.profileLinkState === 'LINKED' && member.linkedStaffProfileId != null) {
    const openButton = el('button', {
      className: 'button-small button-secondary venue-staff-profile-link-action',
      text: 'Открыть карточку'
    }) as HTMLButtonElement
    openButton.addEventListener('click', () => onOpenProfile(member))
    actions.appendChild(openButton)
  }

  if (canManageRoles && member.userId !== currentUserId) {
    const roleSelect = document.createElement('select')
    roleSelect.className = 'venue-select'
    roleSelect.setAttribute('aria-label', `Роль: ${member.displayName}`)
    roleSelect.appendChild(new Option('Владелец', 'OWNER'))
    roleSelect.appendChild(new Option('Менеджер', 'MANAGER'))
    roleSelect.appendChild(new Option('Сотрудник', 'STAFF'))
    roleSelect.value = member.role
    const updateButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
    const removeButton = el('button', { className: 'button-small button-secondary', text: 'Удалить доступ' }) as HTMLButtonElement

    updateButton.addEventListener('click', () => onUpdateRole(member, roleSelect.value as VenueStaffMemberDto['role']))
    removeButton.addEventListener('click', () => onRemove(member))
    append(actions, roleSelect, updateButton, removeButton)
  }

  append(row, info, actions)
  return row
}

function formatProfileSubtype(subtype: VenueStaffProfileSubtype): string {
  switch (subtype) {
    case 'hookah_master':
      return 'Кальянный мастер'
    case 'waiter':
      return 'Официант'
    case 'admin':
      return 'Администратор'
    case 'other':
      return 'Сотрудник'
    default:
      return subtype
  }
}

function isOtherProfileSubtype(subtype: VenueStaffProfileSubtype | null | undefined): boolean {
  return subtype === 'other'
}

function formatProfileRole(profile: VenueStaffProfileDto): string {
  const customRole = profile.roleLabel?.trim()
  if (customRole) return customRole
  return formatProfileSubtype(profile.subtype)
}

function updateRoleLabelField(
  field: HTMLElement,
  input: HTMLInputElement,
  subtype: VenueStaffProfileSubtype | null | undefined
) {
  const visible = isOtherProfileSubtype(subtype)
  field.hidden = !visible
  input.required = visible
}

function formatShiftStatus(status: VenueStaffShiftStatus | undefined | null): string {
  switch (status) {
    case 'scheduled':
      return 'Запланирован на сегодня'
    case 'active':
      return 'Сегодня на смене'
    case 'completed':
      return 'Не на смене сегодня'
    case 'canceled':
      return 'Не на смене сегодня'
    default:
      return 'Не на смене сегодня'
  }
}

function splitTags(value: string): string[] {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean)
    .slice(0, 8)
}

function parseLinkedUserValue(value: string): number | null {
  const trimmed = value.trim()
  if (!trimmed) return null
  const parsed = Number(trimmed)
  return Number.isInteger(parsed) && parsed > 0 ? parsed : Number.NaN
}

const DUPLICATE_LINK_WARNING =
  'К этому сотруднику привязано несколько карточек. Выберите основную и отвяжите остальные.'

function formatMemberUsername(member: VenueStaffMemberDto): string {
  const username = member.username?.trim().replace(/^@+/, '')
  if (username) return `@${username}`
  return `Без username · ID …${String(member.userId).slice(-4).padStart(4, '0')}`
}

function formatLinkedMemberOption(member: VenueStaffMemberDto): string {
  return `${member.displayName} · ${formatMemberUsername(member)} · ${formatStaffRole(member.role)}`
}

function formatMemberLinkState(member: VenueStaffMemberDto): string {
  switch (member.profileLinkState) {
    case 'NOT_LINKED':
      return 'Нет привязанной карточки'
    case 'LINKED':
      return member.linkedStaffProfileDisplayName?.trim()
        ? `Привязан к карточке «${member.linkedStaffProfileDisplayName.trim()}»`
        : 'Карточка связана с сотрудником'
    case 'DUPLICATE_LINK_DETECTED':
      return 'Обнаружено несколько активных карточек'
    case 'PROTECTED':
      return 'Защищённая привязка'
    default:
      return member.profileLinkState
  }
}

function linkedMemberOptionSuffix(member: VenueStaffMemberDto): string | null {
  switch (member.profileLinkState) {
    case 'LINKED':
      return member.linkedStaffProfileDisplayName?.trim()
        ? `Уже привязан к карточке «${member.linkedStaffProfileDisplayName.trim()}»`
        : 'Уже привязан к карточке'
    case 'DUPLICATE_LINK_DETECTED':
      return 'Привязано несколько карточек'
    case 'PROTECTED':
      return 'Недоступен для привязки'
    default:
      return null
  }
}

type StaffProfileLinkConflictDetails = {
  profileLinkState: VenueStaffMemberDto['profileLinkState'] | null
  linkedStaffProfileId: number | null
}

function parseStaffProfileLinkConflictDetails(details: unknown): StaffProfileLinkConflictDetails {
  if (!details || typeof details !== 'object') {
    return { profileLinkState: null, linkedStaffProfileId: null }
  }
  const record = details as Record<string, unknown>
  const rawState = record.profileLinkState
  const profileLinkState =
    rawState === 'NOT_LINKED' ||
    rawState === 'LINKED' ||
    rawState === 'DUPLICATE_LINK_DETECTED' ||
    rawState === 'PROTECTED'
      ? rawState
      : null
  const nestedRef =
    record.linkedStaffProfileRef && typeof record.linkedStaffProfileRef === 'object'
      ? (record.linkedStaffProfileRef as Record<string, unknown>)
      : null
  const rawProfileId =
    record.linkedStaffProfileId ??
    record.staffProfileId ??
    record.existingStaffProfileId ??
    nestedRef?.id
  const linkedStaffProfileId =
    typeof rawProfileId === 'number' && Number.isInteger(rawProfileId) && rawProfileId > 0
      ? rawProfileId
      : null
  return { profileLinkState, linkedStaffProfileId }
}

function populateLinkedUserSelect(
  select: HTMLSelectElement,
  members: VenueStaffMemberDto[],
  selectedUserId: number | null | undefined,
  allowedRoles?: ReadonlySet<VenueStaffMemberDto['role']>
) {
  const selectedValue = selectedUserId ? String(selectedUserId) : ''
  select.replaceChildren(new Option('Не привязывать — просто карточка для гостей', ''))
  members
    .filter((member) => !allowedRoles || allowedRoles.has(member.role))
    .forEach((member) => {
      const isCurrentSelection = String(member.userId) === selectedValue
      const suffix = isCurrentSelection ? null : linkedMemberOptionSuffix(member)
      const option = new Option(
        [formatLinkedMemberOption(member), suffix].filter(Boolean).join(' · '),
        String(member.userId)
      )
      option.disabled = !isCurrentSelection && (!member.active || member.profileLinkState !== 'NOT_LINKED')
      select.appendChild(option)
    })
  if (selectedValue && !Array.from(select.options).some((option) => option.value === selectedValue)) {
    select.appendChild(new Option('Текущая привязка недоступна', selectedValue))
  }
  select.value = Array.from(select.options).some((option) => option.value === selectedValue) ? selectedValue : ''
}

function normalizeOptionalText(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function formatScheduledShiftLine(profile: VenueStaffProfileDto): string {
  const shift = profile.todayShift
  if (!shift?.startsAt || !shift.endsAt) return 'не запланирован сегодня'
  return `запланирован сегодня · ${shift.startsAt}-${shift.endsAt} · ${formatShiftStatus(shift.status)}`
}

type GuestVisibilitySnapshot = {
  profileIds: Set<number>
  venuePublished: boolean
}

function formatProfileVisibility(
  profile: VenueStaffProfileDto,
  moduleSettings: VenueStaffModuleSettingsDto | null,
  guestVisibility: GuestVisibilitySnapshot | null
): string {
  const published = profile.isGuestVisible && Boolean(profile.publishedAt) && !profile.disabledAt
  let visibility = 'Видимость для гостей не проверена · обновите данные'
  if (!published) {
    visibility = 'Не видна гостям · карточка скрыта'
  } else if (moduleSettings?.guestTeamVisible === false) {
    visibility = 'Не видна гостям · показ команды отключён'
  } else if (guestVisibility !== null) {
    if (!guestVisibility.venuePublished) {
      visibility = 'Не видна гостям · заведение не опубликовано'
    } else if (moduleSettings?.todayStaffSource === 'MANUAL') {
      visibility = guestVisibility.profileIds.has(profile.id)
        ? 'Видна гостям · включена вручную'
        : 'Не видна гостям · показ на сегодня выключен'
    } else if (moduleSettings?.todayStaffSource === 'SCHEDULE') {
      visibility = guestVisibility.profileIds.has(profile.id)
        ? 'Видна гостям · активная смена'
        : 'Не видна гостям · нет активной смены'
    }
  }
  return visibility
}

type ProfileEditorDraft = {
  name: string
  subtype: string
  roleLabel: string
  linkedUser: string
  bio: string
  tags: string
}

function renderProfileRow(
  profile: VenueStaffProfileDto,
  access: VenueAccessDto,
  moduleSettings: VenueStaffModuleSettingsDto | null,
  staffMembers: VenueStaffMemberDto[],
  guestVisibility: GuestVisibilitySnapshot | null,
  directoryReady: boolean,
  isEditing: boolean,
  todayMutationPending: boolean,
  draft: ProfileEditorDraft | undefined,
  handlers: {
    onDraftChange: (draft: ProfileEditorDraft) => void
    onCollapse: () => void
    onSave: (profile: VenueStaffProfileDto, draft: {
      displayName?: string | null
      roleLabel?: string | null
      subtype?: VenueStaffProfileSubtype | null
      linkedUserId?: number | null
      unlinkUser?: boolean
      bio?: string | null
      tags?: string[] | null
    }) => void
    onEdit: (profile: VenueStaffProfileDto) => void
    onCancelEdit: () => void
    onPublish: (profile: VenueStaffProfileDto) => void
    onHide: (profile: VenueStaffProfileDto) => void
    onShift: (profile: VenueStaffProfileDto, status: VenueStaffShiftStatus, isGuestVisible: boolean) => void
  }
) {
  const canManageProfile = directoryReady && profile.canManage === true
  const canPublishProfile =
    canManageProfile &&
    (access.role === 'OWNER' || access.permissions.includes('STAFF_PROFILE_PUBLISH_STAFF'))
  const canEditOwn =
    directoryReady &&
    profile.isSelf === true &&
    access.permissions.includes('STAFF_PROFILE_EDIT_OWN')
  const canEdit = canManageProfile || canEditOwn
  const canManageShift =
    canManageProfile &&
    access.permissions.includes('STAFF_SCHEDULE_MANAGE')

  const row = el('div', { className: 'venue-staff-row venue-profile-row' })
  row.dataset.staffProfileId = String(profile.id)
  const info = el('div', { className: 'venue-staff-info' })
  const manualGuestVisible =
    profile.todayShift?.isGuestVisible === true &&
    (profile.todayShift.status === 'active' || profile.todayShift.status === 'scheduled')
  if (moduleSettings?.todayStaffSource === 'SCHEDULE') {
    const scheduleDetails = document.createElement('details')
    scheduleDetails.className = 'venue-profile-schedule-details'
    append(scheduleDetails,
      el('summary', { text: 'Смена сегодня' }),
      el('p', { className: 'venue-order-sub', text: `По графику: ${formatScheduledShiftLine(profile)}` })
    )
    info.appendChild(scheduleDetails)
  }
  if (profile.tags?.length) {
    info.appendChild(el('p', { className: 'venue-order-sub', text: profile.tags.join(', ') }))
  }
  if (profile.bio) {
    info.appendChild(el('p', { className: 'venue-profile-bio', text: profile.bio }))
  }
  if (
    access.role === 'MANAGER' &&
    profile.linkageClass === 'PROTECTED' &&
    !profile.isSelf
  ) {
    info.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: 'Данные карточки меняет владелец. Фотографией также может управлять менеджер.'
      })
    )
  }

  const actions = el('div', { className: 'venue-staff-actions venue-profile-actions' })

  if (canEdit && isEditing) {
    const nameInput = document.createElement('input')
    nameInput.className = 'venue-input'
    nameInput.value = draft?.name ?? profile.displayName
    nameInput.placeholder = 'Например: Максим'
    nameInput.maxLength = 120
    const subtypeSelect = document.createElement('select')
    subtypeSelect.className = 'venue-select'
    ;[
      ['Кальянный мастер', 'hookah_master'],
      ['Официант', 'waiter'],
      ['Администратор', 'admin'],
      ['Другое', 'other']
    ].forEach(([label, value]) => subtypeSelect.appendChild(new Option(label, value)))
    subtypeSelect.value = draft?.subtype ?? (profile.subtype || 'other')
    const roleLabelInput = document.createElement('input')
    roleLabelInput.className = 'venue-input'
    roleLabelInput.value = draft?.roleLabel ?? profile.roleLabel ?? ''
    roleLabelInput.placeholder = 'Например: Бармен, Старший смены, Мастер миксов'
    roleLabelInput.maxLength = 120
    const roleLabelField = renderProfileField('Название роли', roleLabelInput, 'Так роль будет показана гостям.')
    updateRoleLabelField(roleLabelField, roleLabelInput, subtypeSelect.value as VenueStaffProfileSubtype)
    subtypeSelect.addEventListener('change', () =>
      updateRoleLabelField(roleLabelField, roleLabelInput, subtypeSelect.value as VenueStaffProfileSubtype)
    )
    const linkedSelect = document.createElement('select')
    linkedSelect.className = 'venue-select'
    populateLinkedUserSelect(
      linkedSelect,
      staffMembers,
      draft ? parseLinkedUserValue(draft.linkedUser) : profile.linkedUserId,
      access.role === 'OWNER' ? undefined : new Set<VenueStaffMemberDto['role']>(['STAFF'])
    )
    const bioInput = document.createElement('textarea')
    bioInput.className = 'venue-textarea'
    bioInput.value = draft?.bio ?? profile.bio ?? ''
    bioInput.placeholder = 'Например: Люблю крепкие миксы и помогаю подобрать вкус под настроение.'
    bioInput.rows = 3
    bioInput.maxLength = 1000
    const tagsInput = document.createElement('input')
    tagsInput.className = 'venue-input'
    tagsInput.value = draft?.tags ?? profile.tags?.join(', ') ?? ''
    const captureDraft = () => handlers.onDraftChange({
      name: nameInput.value, subtype: subtypeSelect.value, roleLabel: roleLabelInput.value,
      linkedUser: linkedSelect.value, bio: bioInput.value, tags: tagsInput.value
    })
    ;[nameInput, subtypeSelect, roleLabelInput, linkedSelect, bioInput, tagsInput].forEach((control) => {
      control.addEventListener('input', captureDraft)
      control.addEventListener('change', captureDraft)
    })
    tagsInput.placeholder = 'Например: крепкие миксы, фруктовые чаши, авторские вкусы'
    const saveButton = el('button', { className: 'button-small', text: 'Сохранить' }) as HTMLButtonElement
    const cancelButton = el('button', { className: 'button-small button-secondary', text: 'Отмена' }) as HTMLButtonElement
    const collapseButton = el('button', { className: 'button-small button-secondary', text: 'Свернуть' }) as HTMLButtonElement
    collapseButton.addEventListener('click', handlers.onCollapse)
    saveButton.addEventListener('click', () => {
      const linkedUserId = parseLinkedUserValue(linkedSelect.value)
      if (Number.isNaN(linkedUserId)) {
        showToast('Некорректная привязка сотрудника')
        return
      }
      const nextSubtype = subtypeSelect.value as VenueStaffProfileSubtype
      const nextRoleLabel = normalizeOptionalText(roleLabelInput.value)
      if (canManageProfile && isOtherProfileSubtype(nextSubtype) && !nextRoleLabel) {
        showToast('Укажите название роли')
        roleLabelInput.focus()
        return
      }
      const linkageChanged = linkedUserId !== (profile.linkedUserId ?? null)
      handlers.onSave(profile, {
        displayName: canManageProfile ? nameInput.value : undefined,
        roleLabel: canManageProfile ? (isOtherProfileSubtype(nextSubtype) ? nextRoleLabel : null) : undefined,
        subtype: canManageProfile ? nextSubtype : undefined,
        linkedUserId:
          canManageProfile && linkageChanged && linkedUserId !== null ? linkedUserId : undefined,
        unlinkUser: canManageProfile && linkageChanged && linkedUserId === null ? true : undefined,
        bio: bioInput.value,
        tags: splitTags(tagsInput.value)
      })
    })
    if (canManageProfile) {
      append(
        actions,
        renderProfileField('Имя на карточке', nameInput, 'Так это имя увидят гости.'),
        renderProfileField('Тип сотрудника', subtypeSelect),
        roleLabelField,
        renderProfileField(
          'Привязать к сотруднику',
          linkedSelect,
          'Привязка нужна, чтобы сотрудник мог позже редактировать своё описание. Гостям эта связь не показывается.'
        )
      )
    }
    append(
      actions,
      renderProfileField('Коротко о сотруднике', bioInput),
      renderProfileField(
        'Специализация',
        tagsInput,
        'Можно указать через запятую. Это поможет гостям понять стиль сотрудника.'
      ),
      saveButton,
      collapseButton,
      cancelButton
    )
    cancelButton.addEventListener('click', handlers.onCancelEdit)
    append(row, info, actions)
    return row
  }

  if (canEdit) {
    const editButton = el('button', { className: 'button-small button-secondary', text: 'Редактировать' }) as HTMLButtonElement
    editButton.addEventListener('click', () => handlers.onEdit(profile))
    actions.appendChild(editButton)
  }

  if (canPublishProfile) {
    const visibilityButton = el('button', {
      className: 'button-small button-secondary',
      text: profile.isGuestVisible && profile.publishedAt && !profile.disabledAt ? 'Скрыть карточку' : 'Опубликовать'
    }) as HTMLButtonElement
    visibilityButton.addEventListener('click', () => {
      if (profile.isGuestVisible && profile.publishedAt && !profile.disabledAt) {
        handlers.onHide(profile)
      } else {
        handlers.onPublish(profile)
      }
    })
    actions.appendChild(visibilityButton)
  }

  if (canManageShift && moduleSettings?.todayStaffSource === 'SCHEDULE') {
    actions.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: 'Состав для гостей определяется активными сменами в графике.'
      })
    )
  }

  if (canManageShift && moduleSettings?.todayStaffSource === 'MANUAL') {
    const shiftHelp = el('p', {
      className: 'venue-order-sub',
      text: 'Показывать сотрудника гостям сегодня'
    })
    const publicationButton = el('button', {
      className: manualGuestVisible ? 'button-small' : 'button-small button-secondary',
      text: manualGuestVisible ? 'Включено' : 'Выключено'
    }) as HTMLButtonElement
    publicationButton.setAttribute('role', 'switch')
    publicationButton.setAttribute('aria-checked', String(manualGuestVisible))
    publicationButton.disabled = todayMutationPending
    publicationButton.addEventListener('click', () =>
      handlers.onShift(
        profile,
        manualGuestVisible ? 'canceled' : 'active',
        !manualGuestVisible
      )
    )
    append(actions, shiftHelp, publicationButton)
  }

  append(row, info, actions)
  return row
}

type CreateProfileMode =
  | { kind: 'DISPLAY_ONLY' }
  | { kind: 'FROM_MEMBER'; userId: number }

export function renderVenueStaffScreen(options: VenueStaffOptions) {
  const {
    root,
    backendUrl,
    isDebug,
    venueId,
    access,
    currentUserId,
    onOpenStaffModuleSettings
  } = options
  if (!root) return () => undefined
  const refs = buildStaffDom(root, access)
  const deps = buildApiDeps(isDebug)

  let section: 'accounts' | 'profiles' = 'accounts'
  const expandedProfiles = new Set<number>()
  const photoPanels = new Map<number, { identity: string; panel: StaffPhotoPanel }>()
  options.setPhotoNavigation?.({ guard: () => [...photoPanels.values()].every(({ panel }) => panel.canLeave()), hasPending: () => [...photoPanels.values()].some(({ panel }) => panel.hasPending()), refresh: () => { void reloadMemberProfiles() } })
  const showSection = (next: 'accounts' | 'profiles') => {
    if (next !== section && ![...photoPanels.values()].every(({ panel }) => panel.canLeave())) return false
    section = next
    refs.accessCard.hidden = section !== 'accounts'
    refs.profileCard.hidden = section !== 'profiles'
    refs.accessTab.setAttribute('aria-pressed', String(section === 'accounts'))
    refs.profilesTab.setAttribute('aria-pressed', String(section === 'profiles'))
    if (section === 'accounts') {
      photoPanels.forEach(({ panel }) => panel.dispose()); photoPanels.clear()
    }
    return true
  }
  showSection('accounts')
  refs.accessTab.addEventListener('click', () => { if (showSection('accounts')) renderProfiles(currentProfiles) })
  refs.profilesTab.addEventListener('click', () => { if (showSection('profiles')) renderProfiles(currentProfiles) })
  let disposed = false
  let loadAbort: AbortController | null = null
  let profileLoadAbort: AbortController | null = null
  let pendingInviteLoadAbort: AbortController | null = null
  let moduleSettingsLoadAbort: AbortController | null = null
  let guestVisibilityLoadAbort: AbortController | null = null
  let guestVisibility: GuestVisibilitySnapshot | null = null
  let profilesReadReady = false
  let moduleSettingsReadReady = false
  let loadSeq = 0
  let profileLoadSeq = 0
  let pendingInviteLoadSeq = 0
  type SavedInvite = {
    response: VenueStaffInviteResponse
    role: 'STAFF' | 'MANAGER'
    expanded: boolean
    ordinal: number
    feedback: string
    fallbackExpanded: boolean
  }
  const sessionInvites = new Map<string, SavedInvite>()
  const legacyInvites: SavedInvite[] = []
  const absentSessionHandles = new Set<string>()
  const revokedInviteHandles = new Set<string>()
  const uncertainInviteHandles = new Set<string>()
  let inviteRecoveryNotice = ''
  const observedExpiredOrdinals = new Set<number>()
  let nextInviteOrdinal = 0
  let currentPendingInvites: VenueStaffPendingInviteDto[] = []
  const revokingInviteHandles = new Set<string>()
  const expandedPendingHandles = new Set<string>()
  let staffMembers: VenueStaffMemberDto[] = []
  let directoryReady = false
  let currentProfiles: VenueStaffProfileDto[] = []
  let isCreateFormOpen = false
  let createProfileMode: CreateProfileMode | null = null
  let editingProfileId: number | null = null
  let pendingOpenProfileId: number | null = null
  let moduleSettings: VenueStaffModuleSettingsDto | null = null
  const todayMutationProfileIds = new Set<number>()
  const profileDrafts = new Map<number, ProfileEditorDraft>()
  const createDrafts = new Map<string, ProfileEditorDraft>()
  const mutatingMembers = new Set<number>()
  const mutatingProfiles = new Set<number>()
  let creatingInvite = false
  let creatingProfile = false

  const moduleEnabled = access.teamScheduleModuleEnabled !== false

  const canInviteStaff =
    access.role === 'OWNER' || access.permissions.includes('STAFF_INVITE_CREATE_STAFF')
  const canInviteManager =
    access.role === 'OWNER' || access.permissions.includes('STAFF_INVITE_CREATE_MANAGER')
  const canInvite = canInviteStaff || canInviteManager
  const canManageRoles = access.role === 'OWNER'
  const canCreateProfiles =
    access.role === 'OWNER' || access.permissions.includes('STAFF_PROFILE_MANAGE_STAFF')
  const canManageProfileShifts =
    access.role === 'OWNER' || access.permissions.includes('STAFF_SCHEDULE_MANAGE')
  const linkableProfileRoles =
    access.role === 'OWNER' ? undefined : new Set<VenueStaffMemberDto['role']>(['STAFF'])

  const canRevokeInvite = (invite: Pick<VenueStaffPendingInviteDto, 'role'>): boolean =>
    invite.role === 'MANAGER'
      ? access.role === 'OWNER' || access.permissions.includes('STAFF_INVITE_REVOKE_MANAGER')
      : access.role === 'OWNER' || access.permissions.includes('STAFF_INVITE_REVOKE_STAFF')

  refs.profileDisabledPanel.hidden = moduleEnabled
  refs.profileEnabledContent.hidden = !moduleEnabled
  refs.profileEnableButton.hidden =
    !access.permissions.includes('STAFF_MODULE_SETTINGS_MANAGE') || !onOpenStaffModuleSettings
  refs.profileEnableButton.addEventListener('click', () => onOpenStaffModuleSettings?.())

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const setProfileStatus = (text: string) => {
    refs.profileStatus.textContent = text
  }

  const syncCreateFormVisibility = () => {
    const formUsable = moduleEnabled && canCreateProfiles && directoryReady
    const isFromMember = createProfileMode?.kind === 'FROM_MEMBER'
    refs.profileForm.hidden = !isCreateFormOpen || !formUsable
    refs.profileAddButton.hidden = isCreateFormOpen || !canCreateProfiles || !moduleEnabled
    refs.profileAddButton.disabled = !formUsable
    refs.profileAddButton.title = formUsable ? '' : 'Дождитесь актуального списка сотрудников'
    refs.profileName.readOnly = false
    refs.profileLinkedUserField.hidden = !isFromMember
    refs.profileLinkedUser.disabled = true
    refs.profilePhotoPlaceholder.hidden = isFromMember
    refs.profileBioField.hidden = isFromMember
    refs.profileTagsField.hidden = isFromMember
    refs.profileCreateButton.textContent = isFromMember ? 'Создать карточку' : 'Создать профиль'
    refs.profileCreateButton.disabled = !formUsable || creatingProfile
    refs.profileCancelButton.disabled = creatingProfile
    refs.profileForm.querySelector<HTMLButtonElement>('[data-action="collapse-create"]')!.disabled = creatingProfile
    ;[refs.profileName, refs.profileSubtype, refs.profileRoleLabel, refs.profileBio, refs.profileTags].forEach((control) => {
      control.disabled = creatingProfile || !formUsable
    })
    refs.profileCreateButton.title = formUsable ? '' : 'Дождитесь актуального списка сотрудников'
    updateRoleLabelField(
      refs.profileRoleLabelField,
      refs.profileRoleLabel,
      refs.profileSubtype.value as VenueStaffProfileSubtype
    )
  }

  const clearCreateFormFields = () => {
    refs.profileName.value = ''
    refs.profileSubtype.value = ''
    refs.profileRoleLabel.value = ''
    refs.profileLinkedUser.value = ''
    refs.profileBio.value = ''
    refs.profileTags.value = ''
  }

  const createDraftKey = (mode: CreateProfileMode) => mode.kind === 'FROM_MEMBER' ? `member:${mode.userId}` : 'display'
  const keepCreateDraft = () => {
    if (!createProfileMode) return
    createDrafts.set(createDraftKey(createProfileMode), {
      name: refs.profileName.value, subtype: refs.profileSubtype.value, roleLabel: refs.profileRoleLabel.value,
      linkedUser: refs.profileLinkedUser.value, bio: refs.profileBio.value, tags: refs.profileTags.value
    })
  }
  const restoreCreateDraft = () => {
    const draft = createProfileMode && createDrafts.get(createDraftKey(createProfileMode))
    if (!draft) return
    refs.profileName.value = draft.name
    refs.profileSubtype.value = draft.subtype
    refs.profileRoleLabel.value = draft.roleLabel
    refs.profileLinkedUser.value = draft.linkedUser
    refs.profileBio.value = draft.bio
    refs.profileTags.value = draft.tags
  }

  const resetCreateForm = () => {
    if (createProfileMode) createDrafts.delete(createDraftKey(createProfileMode))
    createProfileMode = null
    clearCreateFormFields()
    isCreateFormOpen = false
    syncCreateFormVisibility()
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
          action.label === 'Повторить' ? { ...action, onClick: () => void reloadAll() } : action
        )
      : [{ label: 'Повторить', kind: 'primary' as const, onClick: () => void reloadAll() }]
    renderErrorActions(refs.errorActions, actions)
    renderErrorDetails(refs.errorDetails, error, { isDebug })
    refs.error.hidden = false
  }

  const openUrl = (url: string) => {
    const telegram = getTelegramContext()
    if (telegram.webApp?.openTelegramLink) {
      try {
        telegram.webApp.openTelegramLink(url)
        return true
      } catch {
        // Fall through to the browser fallback below.
      }
    }
    try {
      return Boolean(window.open(url, '_blank', 'noopener,noreferrer'))
    } catch {
      return false
    }
  }

  const inviteState = (handle: string | null, expiresAt: string) => {
    if (handle && revokedInviteHandles.has(handle)) return 'revoked'
    if (handle && uncertainInviteHandles.has(handle)) return 'unknown'
    if (Date.parse(expiresAt) <= Date.now()) return 'expired'
    if (handle && absentSessionHandles.has(handle)) return 'unlisted'
    return 'pending'
  }

  const renderPendingInvites = () => {
    const activeField = document.activeElement instanceof HTMLTextAreaElement && refs.pendingInviteList.contains(document.activeElement)
      ? { id: document.activeElement.id, start: document.activeElement.selectionStart, end: document.activeElement.selectionEnd }
      : null
    refs.pendingInviteList.querySelectorAll<HTMLDetailsElement>('[data-invite-key]').forEach((row) => {
      const saved = row.dataset.inviteHandle
        ? sessionInvites.get(row.dataset.inviteHandle)
        : legacyInvites.find((invite) => String(invite.ordinal) === row.dataset.inviteKey)
      if (saved && row.dataset.inviteKey === String(saved.ordinal)) {
        saved.expanded = row.open
        saved.fallbackExpanded = row.querySelector<HTMLDetailsElement>('.venue-invite-fallback')?.open ?? false
      } else if (!saved && row.dataset.inviteHandle) {
        if (row.open) expandedPendingHandles.add(row.dataset.inviteHandle)
        else expandedPendingHandles.delete(row.dataset.inviteHandle)
      }
    })
    refs.pendingInviteList.replaceChildren()
    if (legacyInvites.length) {
      refs.pendingInviteList.appendChild(el('p', {
        className: 'field-help',
        text: 'Не удалось связать созданную ссылку со списком. Ссылки этой сессии сохранены; отзыв недоступен до обновления сервера и перезагрузки раздела.'
      }))
    }
    const rows = new Map<string, { pending?: VenueStaffPendingInviteDto; saved?: SavedInvite }>()
    Array.from(sessionInvites.entries()).reverse().forEach(([handle, saved]) => {
      if (access.role !== 'MANAGER' || saved.role === 'STAFF') rows.set(handle, { saved })
    })
    if (!legacyInvites.length) currentPendingInvites.forEach((invite) => {
      if (Date.parse(invite.expiresAt) > Date.now() && !revokedInviteHandles.has(invite.handle) && (access.role !== 'MANAGER' || invite.role === 'STAFF')) {
        rows.set(invite.handle, { ...rows.get(invite.handle), pending: invite })
      }
    })
    if (!rows.size && !legacyInvites.length) {
      refs.pendingInviteList.appendChild(el('p', { className: 'venue-empty', text: 'Действующих приглашений нет.' }))
    }
    const renderRow = (handle: string | null, pending: VenueStaffPendingInviteDto | undefined, saved: SavedInvite | undefined) => {
      const role = pending?.role ?? saved!.role
      const expiresAt = pending?.expiresAt ?? saved!.response.expiresAt
      const state = inviteState(handle, expiresAt)
      const stateText = state === 'unknown' ? 'Результат перевыпуска не подтверждён' : state === 'revoked' ? 'Приглашение отозвано'
        : state === 'expired' ? 'Срок действия истёк'
          : state === 'unlisted' ? 'Нет в действующих приглашениях' : 'Ссылка создана'
      if (saved && Date.parse(expiresAt) <= Date.now()) observedExpiredOrdinals.add(saved.ordinal)
      const row = document.createElement('details')
      row.className = handle ? 'venue-pending-invite-row' : 'venue-legacy-invite-row'
      row.dataset.inviteKey = saved ? String(saved.ordinal) : handle!
      row.dataset.inviteState = state
      if (handle) row.dataset.inviteHandle = handle
      row.open = saved?.expanded ?? expandedPendingHandles.has(handle!)
      const summary = el('summary', { text: `${stateText} · ${formatStaffRole(role)} · до ${formatInviteExpires(expiresAt)}` })
      const body = el('div', { className: saved ? 'venue-invite-result' : 'venue-pending-invite-details' })
      body.appendChild(el('p', { className: 'venue-invite-state', text: stateText }))
      if (pending) body.appendChild(el('p', { className: 'venue-order-sub', text: `Создано: ${formatInviteExpires(pending.createdAt)}` }))
      const stateHelp = state === 'unknown' ? 'Не отправляйте прежнюю ссылку. Обновите список приглашений, чтобы проверить состояние.' : state === 'revoked' ? 'Ссылка больше не выдаёт доступ. Уже выданные доступы не изменены.'
        : state === 'expired' ? 'Создайте новое приглашение, если доступ ещё нужен.'
          : state === 'unlisted' ? 'Причина не сообщается: ссылка могла быть принята, отозвана или стать недоступной. Проверьте текущие доступы.'
            : 'Получатель до принятия неизвестен. Доступ выдаётся только после принятия в Telegram.'
      if (saved) {
        const invite = saved.response
        const venueName = invite.venueName ?? access.venueName ?? `Заведение ${venueId}`
        body.appendChild(el('p', { className: 'venue-invite-help', text: `${formatStaffRole(role)} · ${venueName} · срок до ${formatInviteExpires(expiresAt)}` }))
        const deepLink = invite.deepLink?.trim() || null
        const command = invite.fallbackCommand ?? `/start ${invite.startPayload ?? `staff_invite_${invite.inviteCode}`}`
        const field = document.createElement('textarea')
        field.id = saved.ordinal === 0 ? 'venue-staff-invite-link' : `venue-staff-invite-link-${saved.ordinal}`
        field.className = 'venue-invite-field'
        field.readOnly = true
        field.rows = 2
        field.value = deepLink ?? 'Ссылка недоступна. Используйте команду ниже.'
        const label = el('label', { className: 'field-label', text: 'Ссылка для сотрудника' }) as HTMLLabelElement
        label.htmlFor = field.id
        const feedback = el('p', { className: 'venue-invite-copy-status', text: saved.feedback })
        feedback.id = `venue-invite-feedback-${saved.ordinal}`
        feedback.setAttribute('aria-live', 'polite')
        const setFeedback = (text: string) => {
          saved.feedback = text
          const current = document.getElementById(feedback.id)
          if (current && refs.pendingInviteList.contains(current)) current.textContent = text
        }
        const usable = () => {
          if (disposed) return false
          const current = inviteState(handle, expiresAt)
          if (current === 'pending') return true
          renderPendingInvites()
          showToast(current === 'expired' ? 'Срок действия ссылки истёк' : 'Ссылка недоступна')
          return false
        }
        const copy = async (text: string, controlId: string, success: string, failure: string) => {
          if (!usable()) return
          setFeedback('')
          try {
            if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
            await navigator.clipboard.writeText(text)
            if (disposed) return
            setFeedback(success)
            showToast(success)
          } catch {
            if (disposed) return
            const control = document.getElementById(controlId)
            if (control instanceof HTMLTextAreaElement && refs.pendingInviteList.contains(control)) selectVisibleField(control)
            setFeedback(failure)
          }
        }
        const copyButton = el('button', { className: 'button-small button-secondary', text: '📋 Скопировать ссылку' }) as HTMLButtonElement
        const shareButton = el('button', { className: 'button-small button-secondary', text: '📤 Поделиться в Telegram' }) as HTMLButtonElement
        copyButton.disabled = state !== 'pending' || !deepLink
        shareButton.disabled = state !== 'pending' || !deepLink
        if (deepLink) shareButton.dataset.shareUrl = buildTelegramShareUrl(deepLink, venueName, formatStaffRole(role))
        copyButton.addEventListener('click', () => { if (deepLink) void copy(deepLink, field.id, 'Ссылка скопирована', 'Не удалось скопировать автоматически. Ссылка выделена ниже.') })
        shareButton.addEventListener('click', () => {
          if (!deepLink || !usable()) return
          const opened = openUrl(buildTelegramShareUrl(deepLink, venueName, formatStaffRole(role)))
          setFeedback(opened
            ? 'Открыто окно Telegram. Отправка и получатель здесь не подтверждаются.'
            : 'Если окно Telegram не появилось, скопируйте ссылку. Отправка и получатель здесь не подтверждаются.')
        })
        const actions = el('div', { className: 'venue-invite-actions' })
        append(actions, copyButton, shareButton)
        const fallback = document.createElement('details')
        fallback.className = 'venue-invite-fallback'
        fallback.open = saved.fallbackExpanded
        const commandField = document.createElement('textarea')
        commandField.id = `venue-staff-invite-command-${saved.ordinal}`
        commandField.className = 'venue-invite-field'
        commandField.readOnly = true
        commandField.rows = 2
        commandField.value = command
        commandField.setAttribute('aria-label', 'Команда, если ссылка не открылась')
        const commandButton = el('button', { className: 'button-small button-secondary', text: '📋 Скопировать команду' }) as HTMLButtonElement
        commandButton.disabled = state !== 'pending'
        commandButton.addEventListener('click', () => void copy(command, commandField.id, 'Команда скопирована', 'Не удалось скопировать автоматически. Команда выделена ниже.'))
        append(fallback,
          el('summary', { text: 'Если ссылка не открылась' }),
          el('p', { className: 'venue-invite-help', text: 'Скопируйте команду и отправьте её сотруднику вручную.' }),
          commandField, commandButton
        )
        const done = el('button', { className: 'button-small button-secondary', text: 'Готово' }) as HTMLButtonElement
        done.addEventListener('click', () => { row.open = false; saved.expanded = false; summary.focus() })
        append(body, label, field, actions,
          el('p', { className: 'venue-invite-help', text: stateHelp }),
          el('p', { className: 'field-help', text: 'Ссылка доступна здесь до выхода из раздела или перезагрузки.' }),
          fallback, feedback, done
        )
      } else {
        append(body, el('p', { className: 'venue-invite-help', text: stateHelp }),
          el('p', { className: 'field-help', text: 'Прежняя ссылка здесь не хранится. Можно перевыпустить её: предыдущая ссылка станет недействительной.' })
        )
      }
      if (handle && state === 'pending' && canRevokeInvite({ role })) {
        const revokeButton = el('button', { className: 'button-small button-secondary', text: 'Отозвать приглашение' }) as HTMLButtonElement
        revokeButton.disabled = revokingInviteHandles.size > 0
        revokeButton.addEventListener('click', () => void revokePendingInvite({ handle, role }))
        body.appendChild(revokeButton)
        if ((role === 'STAFF' && canInviteStaff) || (role === 'MANAGER' && canInviteManager)) {
          const reissueButton = el('button', { className: 'button-small button-secondary', text: 'Перевыпустить ссылку' }) as HTMLButtonElement
          reissueButton.disabled = revokingInviteHandles.size > 0
          reissueButton.addEventListener('click', () => void reissuePendingInvite({ handle, role }))
          body.appendChild(reissueButton)
        }
      }
      row.addEventListener('toggle', () => {
        if (!row.isConnected) return
        if (saved) saved.expanded = row.open
        else if (handle) {
          if (row.open) expandedPendingHandles.add(handle)
          else expandedPendingHandles.delete(handle)
        }
      })
      append(row, summary, body)
      refs.pendingInviteList.appendChild(row)
    }
    rows.forEach(({ pending, saved }, handle) => renderRow(handle, pending, saved))
    legacyInvites.forEach((saved) => renderRow(null, undefined, saved))
    if (activeField) {
      const field = document.getElementById(activeField.id)
      if (field instanceof HTMLTextAreaElement && refs.pendingInviteList.contains(field)) {
        field.focus({ preventScroll: true })
        field.setSelectionRange(activeField.start, activeField.end)
      }
    }
  }

  const renderStaff = (members: VenueStaffMemberDto[], currentUserId: number) => {
    refs.list.replaceChildren()
    staffMembers = members.filter(
      (member) => member.active && (access.role === 'OWNER' || member.role === 'STAFF' || member.userId === currentUserId)
    )
    syncCreateFormVisibility()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      staffMembers,
      createProfileMode?.kind === 'FROM_MEMBER' ? createProfileMode.userId : null,
      linkableProfileRoles
    )
    if (currentProfiles.length) {
      renderProfiles(currentProfiles)
    }
    if (!staffMembers.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Сотрудники не найдены.' }))
      return
    }
    staffMembers.forEach((member) => {
      refs.list.appendChild(
        renderMemberRow(
          member,
          currentUserId,
          canManageRoles,
          canCreateProfiles && moduleEnabled,
          (target, role) => void updateRole(target, role),
          (target) => void removeMember(target),
          (target) => openCreateFormForMember(target),
          (target) => openProfileForMember(target)
        )
      )
    })
    syncMemberPending()
  }

  const syncMemberPending = () => {
    refs.list.querySelectorAll<HTMLElement>('[data-member-id]').forEach((row) => {
      const id = Number(row.dataset.memberId)
      const pending = creatingProfile || mutatingMembers.has(id)
      row.setAttribute('aria-busy', String(pending))
      row.querySelectorAll<HTMLButtonElement | HTMLSelectElement>('button, select').forEach((control) => {
        control.disabled = pending || !directoryReady || (id === currentUserId && !control.classList.contains('venue-staff-profile-link-action'))
      })
    })
  }

  const syncProfilePending = () => {
    refs.profileList.querySelectorAll<HTMLElement>('[data-staff-profile-id]').forEach((row) => {
      const pending = creatingProfile || mutatingProfiles.has(Number(row.dataset.staffProfileId))
      row.setAttribute('aria-busy', String(pending))
      if (pending) row.querySelectorAll<HTMLInputElement | HTMLButtonElement | HTMLSelectElement | HTMLTextAreaElement>('button, input, select, textarea').forEach((control) => { control.disabled = true })
    })
  }

  const focusPendingProfile = () => {
    const profileId = pendingOpenProfileId
    if (profileId == null) return
    const profileRow = refs.profileList.querySelector<HTMLElement>(`[data-staff-profile-id="${profileId}"]`)
    if (!profileRow) return
    pendingOpenProfileId = null
    profileRow.tabIndex = -1
    profileRow.focus({ preventScroll: true })
    profileRow.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }

  const renderProfiles = (profiles: VenueStaffProfileDto[]) => {
    if (editingProfileId != null) expandedProfiles.add(editingProfileId)
    for (const [id, entry] of photoPanels) {
      if (!profiles.some((profile) => profile.id === id)) { entry.panel.dispose(); photoPanels.delete(id) }
    }
    refs.profileList.replaceChildren()
    if (!profiles.length) {
      refs.profileList.appendChild(el('p', { className: 'venue-empty', text: 'Профили не найдены.' }))
      return
    }
    profiles.forEach((profile) => {
      const row = renderProfileRow(
          profile,
          access,
          moduleSettings,
          staffMembers,
          guestVisibility,
          directoryReady,
          editingProfileId === profile.id,
          todayMutationProfileIds.has(profile.id),
          profileDrafts.get(profile.id),
          {
          onDraftChange: (draft) => profileDrafts.set(profile.id, draft),
          onCollapse: () => { editingProfileId = null; renderProfiles(currentProfiles) },
          onSave: (target, draft) => void saveProfile(target, draft),
          onEdit: (target) => {
            if (creatingProfile) return
            keepCreateDraft()
            editingProfileId = target.id
            isCreateFormOpen = false
            syncCreateFormVisibility()
            renderProfiles(currentProfiles)
          },
          onCancelEdit: () => {
            if (editingProfileId != null) profileDrafts.delete(editingProfileId)
            editingProfileId = null
            renderProfiles(currentProfiles)
          },
          onPublish: (target) => void publishProfile(target),
          onHide: (target) => void hideProfile(target),
          onShift: (target, status, isGuestVisible) => void updateTodayShift(target, status, isGuestVisible)
          }
        )
      const canViewPhotos = access.role === 'OWNER' || access.role === 'MANAGER' || profile.isSelf
      if (!canViewPhotos) { photoPanels.get(profile.id)?.panel.dispose(); photoPanels.delete(profile.id) }
      const expanded = expandedProfiles.has(profile.id) || editingProfileId === profile.id
      if (section === 'profiles' && expanded && canViewPhotos) {
        const identity = JSON.stringify([profile.linkedUserId, profile.linkageClass, profile.canManage, profile.isSelf])
        let entry = photoPanels.get(profile.id)
        if (entry && entry.identity !== identity) { entry.panel.dispose(); photoPanels.delete(profile.id); entry = undefined }
        if (!entry) { entry = { identity, panel: createStaffPhotoPanel(backendUrl, isDebug, venueId, profile.id, undefined, options.onPhotoChange) }; photoPanels.set(profile.id, entry) }
        row.querySelector('.venue-staff-info')?.appendChild(entry.panel.element)
      }
      const details = el('div', { className: 'venue-profile-details' })
      details.id = `staff-profile-details-${profile.id}`
      details.hidden = !expanded
      details.append(...Array.from(row.childNodes))
      const summary = el('button', { className: 'venue-profile-summary button-secondary' }) as HTMLButtonElement
      summary.setAttribute('aria-expanded', String(expanded))
      summary.setAttribute('aria-controls', details.id)
      const avatar = el('span', { className: 'venue-profile-avatar', text: profile.displayName.trim().slice(0, 1).toUpperCase() || '•' })
      avatar.setAttribute('aria-hidden', 'true')
      const label = el('span', { className: 'venue-profile-summary-label' })
      append(label, el('strong', { text: profile.displayName }), el('span', { text: formatProfileRole(profile) }),
        el('span', { className: 'venue-profile-visibility', text: formatProfileVisibility(profile, moduleSettings, guestVisibility) }))
      append(summary, avatar, label)
      summary.addEventListener('click', () => {
        if (expanded) {
          if (!photoPanels.get(profile.id)?.panel.canLeave() && photoPanels.has(profile.id)) return
          photoPanels.get(profile.id)?.panel.dispose(); photoPanels.delete(profile.id)
          expandedProfiles.delete(profile.id)
          if (editingProfileId === profile.id) editingProfileId = null
        } else expandedProfiles.add(profile.id)
        renderProfiles(currentProfiles)
        refs.profileList.querySelector<HTMLButtonElement>(`[data-staff-profile-id="${profile.id}"] .venue-profile-summary`)?.focus({ preventScroll: true })
      })
      append(row, summary, details)
      refs.profileList.appendChild(row)
    })
    syncProfilePending()
    focusPendingProfile()
  }

  const openCreateFormForMember = (member: VenueStaffMemberDto) => {
    if (
      !directoryReady ||
      !moduleEnabled ||
      !canCreateProfiles ||
      !member.active ||
      member.profileLinkState !== 'NOT_LINKED'
    ) {
      showToast('Карточку для этого сотрудника сейчас создать нельзя')
      return
    }
    if (creatingProfile) return
    if (!showSection('profiles')) return
    keepCreateDraft()
    clearCreateFormFields()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      staffMembers,
      member.userId,
      linkableProfileRoles
    )
    refs.profileName.value = member.displayName.trim()
    refs.profileLinkedUser.value = String(member.userId)
    createProfileMode = { kind: 'FROM_MEMBER', userId: member.userId }
    restoreCreateDraft()
    editingProfileId = null
    isCreateFormOpen = true
    syncCreateFormVisibility()
    renderProfiles(currentProfiles)
    refs.profileSubtype.focus()
    refs.profileForm.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const openProfileForMember = (member: VenueStaffMemberDto) => {
    if (creatingProfile) return
    if (!moduleEnabled) {
      showToast('Карточки команды отключены в настройках')
      return
    }
    const profileId = member.linkedStaffProfileId
    if (member.profileLinkState !== 'LINKED' || profileId == null) {
      showToast('Связанная карточка недоступна')
      return
    }
    keepCreateDraft()
    isCreateFormOpen = false
    syncCreateFormVisibility()
    if (!showSection('profiles')) return
    expandedProfiles.add(profileId)
    editingProfileId = profileId
    pendingOpenProfileId = profileId
    renderProfiles(currentProfiles)
  }

  const loadStaff = async () => {
    if (access.role === 'STAFF') {
      directoryReady = true
      staffMembers = []
      populateLinkedUserSelect(
        refs.profileLinkedUser,
        staffMembers,
        null,
        linkableProfileRoles
      )
      refs.list.replaceChildren(el('p', { className: 'venue-empty', text: 'Управление ролями недоступно.' }))
      if (currentProfiles.length) {
        renderProfiles(currentProfiles)
      }
      setStatus('')
      return
    }
    directoryReady = false
    staffMembers = []
    pendingOpenProfileId = null
    keepCreateDraft()
    syncCreateFormVisibility()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      staffMembers,
      null,
      linkableProfileRoles
    )
    refs.list.replaceChildren()
    if (currentProfiles.length) {
      renderProfiles(currentProfiles)
    }
    hideError()
    setStatus('Загрузка...')
    if (loadAbort) {
      loadAbort.abort()
    }
    const controller = new AbortController()
    loadAbort = controller
    const seq = ++loadSeq
    const result = await venueGetStaff(backendUrl, venueId, deps, controller.signal)
    if (disposed || loadSeq !== seq) return
    loadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setStatus('')
      return
    }
    directoryReady = true
    renderStaff(result.data.members, currentUserId)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const updateGuestVisibilityLabels = () => {
    currentProfiles.forEach((profile) => {
      const labels = refs.profileList.querySelectorAll<HTMLElement>(`[data-staff-profile-id="${profile.id}"] .venue-profile-visibility`)
      labels.forEach((label) => { label.textContent = formatProfileVisibility(profile, moduleSettings, guestVisibility) })
    })
  }

  const invalidateGuestVisibility = () => {
    guestVisibilityLoadAbort?.abort()
    guestVisibilityLoadAbort = null
    guestVisibility = null
    updateGuestVisibilityLabels()
  }

  const loadGuestVisibility = async () => {
    invalidateGuestVisibility()
    if (!profilesReadReady || !moduleSettingsReadReady || !moduleSettings || mutatingProfiles.size || todayMutationProfileIds.size) return
    const controller = new AbortController()
    guestVisibilityLoadAbort = controller
    updateGuestVisibilityLabels()
    const result = await venueGetGuestPreview(backendUrl, venueId, deps, controller.signal)
    if (disposed || guestVisibilityLoadAbort !== controller) return
    guestVisibilityLoadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      updateGuestVisibilityLabels()
      showError(result.error)
      return
    }
    guestVisibility = {
      venuePublished: result.data.mode === 'PUBLISHED_PUBLIC',
      profileIds: new Set((result.data.venue.todayStaff ?? []).map((person) => person.id))
    }
    updateGuestVisibilityLabels()
  }

  const loadProfiles = async () => {
    profilesReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    if (!moduleEnabled) {
      currentProfiles = []
      refs.profileList.replaceChildren()
      setProfileStatus('')
      return
    }
    hideError()
    setProfileStatus('Загрузка...')
    if (profileLoadAbort) {
      profileLoadAbort.abort()
    }
    const controller = new AbortController()
    profileLoadAbort = controller
    const seq = ++profileLoadSeq
    const result = await venueGetStaffProfiles(backendUrl, venueId, deps, controller.signal)
    if (disposed || profileLoadSeq !== seq) return
    profileLoadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      showError(result.error)
      setProfileStatus('')
      return
    }
    currentProfiles = result.data.profiles
    profilesReadReady = true
    void loadGuestVisibility()
    const existingPanels = new Map(photoPanels)
    renderProfiles(result.data.profiles)
    for (const [id, entry] of photoPanels) {
      if (existingPanels.get(id) === entry) void entry.panel.refresh()
    }
    setProfileStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const loadModuleSettings = async () => {
    moduleSettingsReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    if (!moduleEnabled || !access.permissions.includes('STAFF_MODULE_SETTINGS_MANAGE')) {
      moduleSettings = null
      return
    }
    moduleSettingsLoadAbort?.abort()
    const controller = new AbortController()
    moduleSettingsLoadAbort = controller
    const result = await venueGetStaffModuleSettings(
      backendUrl,
      venueId,
      deps,
      controller.signal
    )
    if (disposed || moduleSettingsLoadAbort !== controller) return
    moduleSettingsLoadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      moduleSettings = null
      renderProfiles(currentProfiles)
      showError(result.error)
      return
    }
    moduleSettings = result.data
    moduleSettingsReadReady = true
    void loadGuestVisibility()
    renderProfiles(currentProfiles)
  }

  const loadPendingInvites = async () => {
    if (!canInvite) {
      currentPendingInvites = []
      renderPendingInvites()
      refs.pendingInviteStatus.textContent = ''
      return
    }
    pendingInviteLoadAbort?.abort()
    const controller = new AbortController()
    pendingInviteLoadAbort = controller
    const seq = ++pendingInviteLoadSeq
    const knownSessionHandles = new Set(sessionInvites.keys())
    refs.pendingInviteStatus.textContent = 'Загрузка приглашений...'
    const result = await venueGetPendingStaffInvites(
      backendUrl,
      venueId,
      deps,
      controller.signal
    )
    if (disposed || pendingInviteLoadAbort !== controller || pendingInviteLoadSeq !== seq) return
    pendingInviteLoadAbort = null
    if (!result.ok && result.error.code === REQUEST_ABORTED_CODE) return
    if (!result.ok) {
      refs.pendingInviteStatus.textContent = inviteRecoveryNotice || 'Не удалось обновить статусы. Показаны последние данные.'
      showError(result.error)
      return
    }
    uncertainInviteHandles.forEach((handle) => { if (!revokingInviteHandles.has(handle)) uncertainInviteHandles.delete(handle) })
    currentPendingInvites = result.data.invites.filter((invite) => !revokedInviteHandles.has(invite.handle))
    knownSessionHandles.forEach((handle) => {
      if (currentPendingInvites.some((invite) => invite.handle === handle)) absentSessionHandles.delete(handle)
      else absentSessionHandles.add(handle)
    })
    renderPendingInvites()
    refs.pendingInviteStatus.textContent = inviteRecoveryNotice
  }

  const reloadAll = () => {
    void loadStaff()
    void loadModuleSettings()
    void loadProfiles()
    void loadPendingInvites()
  }

  const reloadMemberProfiles = async () => {
    await Promise.all([loadStaff(), loadModuleSettings(), loadProfiles()])
  }

  const handleProfileLinkConflict = async (error: ApiErrorInfo, clearCreateDraft = false, sourceProfileId?: number) => {
    const details = parseStaffProfileLinkConflictDetails(error.details)
    if (clearCreateDraft) resetCreateForm()
    hideError()
    await reloadMemberProfiles()
    if (disposed) return
    if (
      directoryReady &&
      (clearCreateDraft || (!isCreateFormOpen && editingProfileId === sourceProfileId)) &&
      details.profileLinkState === 'LINKED' &&
      details.linkedStaffProfileId != null
    ) {
      editingProfileId = details.linkedStaffProfileId
      pendingOpenProfileId = details.linkedStaffProfileId
      renderProfiles(currentProfiles)
    }
    showToast(
      details.profileLinkState === 'DUPLICATE_LINK_DETECTED'
        ? DUPLICATE_LINK_WARNING
        : details.profileLinkState === 'LINKED' && details.linkedStaffProfileId != null
          ? 'Карточка сотрудника уже существует. Открываем её.'
          : 'Карточка сотрудника уже существует.'
    )
  }

  const revokePendingInvite = async (invite: Pick<VenueStaffPendingInviteDto, 'handle' | 'role'>) => {
    const expiresAt = sessionInvites.get(invite.handle)?.response.expiresAt ?? currentPendingInvites.find((current) => current.handle === invite.handle)?.expiresAt
    if (!canRevokeInvite(invite) || revokingInviteHandles.size > 0 || !expiresAt || inviteState(invite.handle, expiresAt) !== 'pending') {
      showToast('Недостаточно прав')
      return
    }
    revokingInviteHandles.add(invite.handle)
    pendingInviteLoadAbort?.abort()
    pendingInviteLoadSeq += 1
    renderPendingInvites()
    const result = await venueRevokeStaffInvite(
      backendUrl,
      { venueId, handle: invite.handle },
      deps
    )
    revokingInviteHandles.delete(invite.handle)
    if (disposed) return
    if (!result.ok) {
      renderPendingInvites()
      showError(result.error)
      return
    }
    showToast('Приглашение отозвано')
    pendingInviteLoadAbort?.abort()
    pendingInviteLoadSeq += 1
    revokedInviteHandles.add(invite.handle)
    absentSessionHandles.delete(invite.handle)
    currentPendingInvites = currentPendingInvites.filter((current) => current.handle !== invite.handle)
    renderPendingInvites()
    void loadPendingInvites()
  }

  const reissuePendingInvite = async (invite: Pick<VenueStaffPendingInviteDto, 'handle' | 'role'>) => {
    const expiresAt = sessionInvites.get(invite.handle)?.response.expiresAt ?? currentPendingInvites.find((item) => item.handle === invite.handle)?.expiresAt
    if (disposed || revokingInviteHandles.size > 0 || !canRevokeInvite(invite) || !expiresAt || inviteState(invite.handle, expiresAt) !== 'pending') return
    if (invite.role === 'MANAGER' ? !canInviteManager : !canInviteStaff) return
    if (!window.confirm(`Перевыпустить ссылку? Предыдущая ссылка станет недействительной. Будет создана новая ссылка с той же ролью: ${formatStaffRole(invite.role)}. Права человеку пока не выдаются.`)) return
    revokingInviteHandles.add(invite.handle)
    uncertainInviteHandles.add(invite.handle)
    pendingInviteLoadAbort?.abort()
    pendingInviteLoadSeq += 1
    renderPendingInvites()
    const result = await venueReissueStaffInvite(backendUrl, { venueId, handle: invite.handle }, deps)
    if (disposed) return
    revokingInviteHandles.delete(invite.handle)
    // A GET started during POST cannot confirm the state after that POST committed.
    pendingInviteLoadAbort?.abort()
    pendingInviteLoadSeq += 1
    if (!result.ok) {
      inviteRecoveryNotice = 'Результат перевыпуска не подтверждён. Запрос не повторён. Обновите список: если появилась новая ссылка без копии, её можно перевыпустить заново.'
      refs.pendingInviteStatus.textContent = inviteRecoveryNotice
      renderPendingInvites()
      showError(result.error)
      void loadPendingInvites()
      return
    }
    inviteRecoveryNotice = ''
    uncertainInviteHandles.delete(invite.handle)
    revokedInviteHandles.add(invite.handle)
    absentSessionHandles.delete(invite.handle)
    sessionInvites.delete(invite.handle)
    currentPendingInvites = currentPendingInvites.filter((item) => item.handle !== invite.handle)
    const saved: SavedInvite = { response: result.data, role: invite.role, expanded: true, ordinal: nextInviteOrdinal++, feedback: '', fallbackExpanded: false }
    const handle = result.data.handle?.trim()
    if (handle) sessionInvites.set(handle, saved)
    else legacyInvites.push(saved)
    renderPendingInvites()
    showToast('Ссылка перевыпущена. Предыдущая больше не действует.')
    void loadPendingInvites()
  }

  const createInvite = async () => {
    if (disposed || creatingInvite) return
    if (!canInvite) {
      showToast('Недостаточно прав')
      return
    }
    const role = refs.inviteRole.value as VenueStaffMemberDto['role']
    if ((role === 'STAFF' && !canInviteStaff) || (role === 'MANAGER' && !canInviteManager)) {
      showToast('Недостаточно прав')
      return
    }
    if (role !== 'STAFF' && role !== 'MANAGER') {
      showToast('Эту роль нельзя добавить через приглашение')
      return
    }
    creatingInvite = true
    hideError()
    refs.inviteButton.disabled = true
    refs.inviteRole.disabled = true
    refs.inviteButton.textContent = 'Создаём приглашение…'
    const result = await venueCreateInvite(backendUrl, { venueId, body: { role } }, deps)
    if (disposed) return
    creatingInvite = false
    refs.inviteButton.disabled = false
    refs.inviteRole.disabled = false
    refs.inviteButton.textContent = 'Создать приглашение'
    if (!result.ok) {
      showError(result.error)
      return
    }
    refs.pendingInviteList.querySelectorAll<HTMLDetailsElement>('[data-invite-key]').forEach((row) => { row.open = false })
    sessionInvites.forEach((saved) => { saved.expanded = false })
    legacyInvites.forEach((saved) => { saved.expanded = false })
    const saved: SavedInvite = { response: result.data, role, expanded: true, ordinal: nextInviteOrdinal++, feedback: '', fallbackExpanded: false }
    const handle = result.data.handle?.trim()
    if (handle) sessionInvites.set(handle, saved)
    else legacyInvites.push(saved)
    renderPendingInvites()
    showToast('Ссылка создана')
    void loadPendingInvites()
  }

  const updateRole = async (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => {
    if (disposed || !directoryReady || !canManageRoles || mutatingMembers.has(member.userId)) return
    if (member.userId === currentUserId) {
      showToast('Нельзя менять свою роль')
      return
    }
    mutatingMembers.add(member.userId)
    syncMemberPending()
    hideError()
    const result = await venueUpdateRole(backendUrl, { venueId, userId: member.userId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      mutatingMembers.delete(member.userId)
      syncMemberPending()
      showError(result.error)
      return
    }
    showToast('Роль обновлена')
    await reloadMemberProfiles()
    mutatingMembers.delete(member.userId)
    if (!disposed) syncMemberPending()
  }

  const removeMember = async (member: VenueStaffMemberDto) => {
    if (disposed || !directoryReady || !canManageRoles || mutatingMembers.has(member.userId)) return
    if (member.userId === currentUserId) {
      showToast('Нельзя удалить себя')
      return
    }
    if (!window.confirm(`Удалить доступ ${member.displayName}? Карточка, смены и история сохранятся. Для возврата доступа потребуется новое приглашение.`)) return
    mutatingMembers.add(member.userId)
    syncMemberPending()
    hideError()
    const result = await venueRemoveStaff(backendUrl, { venueId, userId: member.userId }, deps)
    if (disposed) return
    if (!result.ok) {
      mutatingMembers.delete(member.userId)
      syncMemberPending()
      showError(result.error)
      return
    }
    showToast('Участник удалён')
    await reloadMemberProfiles()
    mutatingMembers.delete(member.userId)
    if (!disposed) syncMemberPending()
  }

  const createProfile = async () => {
    if (disposed || creatingProfile) return
    if (!canCreateProfiles || !directoryReady || !createProfileMode) {
      showToast('Недостаточно прав')
      return
    }
    const mode = createProfileMode
    const displayName = refs.profileName.value.trim()
    if (!displayName) {
      showToast('Укажите имя')
      return
    }
    const subtype = refs.profileSubtype.value as VenueStaffProfileSubtype
    if (!subtype) {
      showToast('Выберите тип сотрудника')
      refs.profileSubtype.focus()
      return
    }
    const roleLabel = normalizeOptionalText(refs.profileRoleLabel.value)
    if (isOtherProfileSubtype(subtype) && !roleLabel) {
      showToast('Укажите название роли')
      refs.profileRoleLabel.focus()
      return
    }
    if (mode.kind === 'FROM_MEMBER') {
      const member = staffMembers.find((candidate) => candidate.userId === mode.userId)
      if (!member || !member.active || member.profileLinkState !== 'NOT_LINKED') {
        showToast('Карточку для этого сотрудника сейчас создать нельзя')
        return
      }
    }
    creatingProfile = true
    keepCreateDraft()
    syncCreateFormVisibility()
    syncMemberPending()
    renderProfiles(currentProfiles)
    hideError()
    const result =
      mode.kind === 'FROM_MEMBER'
        ? await venueCreateStaffProfileFromMember(
          backendUrl,
          {
            venueId,
            body: {
              userId: mode.userId,
              displayName,
              subtype,
              ...(isOtherProfileSubtype(subtype) && roleLabel ? { roleLabel } : {})
            }
          },
          deps
        )
        : await venueCreateStaffProfile(
          backendUrl,
          {
            venueId,
            body: {
              displayName,
              roleLabel: isOtherProfileSubtype(subtype) ? roleLabel : null,
              subtype,
              bio: normalizeOptionalText(refs.profileBio.value),
              tags: splitTags(refs.profileTags.value)
            }
          },
          deps
        )
    if (disposed) return
    creatingProfile = false
    syncCreateFormVisibility()
    syncMemberPending()
    renderProfiles(currentProfiles)
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error, true)
        return
      }
      showError(result.error)
      return
    }
    const createdProfileId = result.data.id
    const shouldOpenDraft = mode.kind === 'FROM_MEMBER'
    resetCreateForm()
    showToast(shouldOpenDraft ? 'Карточка создана' : 'Профиль создан')
    if (!shouldOpenDraft) {
      void reloadMemberProfiles()
      return
    }
    await reloadMemberProfiles()
    if (disposed || !directoryReady) return
    editingProfileId = createdProfileId
    pendingOpenProfileId = createdProfileId
    renderProfiles(currentProfiles)
  }

  const saveProfile = async (
    profile: VenueStaffProfileDto,
    draft: {
      displayName?: string | null
      roleLabel?: string | null
      subtype?: VenueStaffProfileSubtype | null
      linkedUserId?: number | null
      unlinkUser?: boolean
      bio?: string | null
      tags?: string[] | null
    }
  ) => {
    if (disposed || !directoryReady || mutatingProfiles.has(profile.id) || todayMutationProfileIds.has(profile.id)) return
    const body: VenueStaffProfileUpdateRequest = {
      displayName: draft.displayName,
      roleLabel: draft.roleLabel === undefined ? undefined : draft.roleLabel === null ? null : normalizeOptionalText(draft.roleLabel),
      subtype: draft.subtype,
      linkedUserId: draft.linkedUserId,
      unlinkUser: draft.unlinkUser,
      bio: draft.bio === undefined ? undefined : draft.bio === null ? null : normalizeOptionalText(draft.bio),
      tags: draft.tags ?? undefined
    }
    mutatingProfiles.add(profile.id)
    profilesReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    hideError()
    const result = await venueUpdateStaffProfile(
      backendUrl,
      {
        venueId,
        profileId: profile.id,
        body
      },
      deps
    )
    if (disposed) return
    mutatingProfiles.delete(profile.id)
    if (!result.ok) {
      renderProfiles(currentProfiles)
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error, false, profile.id)
        return
      }
      showError(result.error)
      return
    }
    showToast('Профиль обновлён')
    profileDrafts.delete(profile.id)
    if (editingProfileId === profile.id) editingProfileId = null
    currentProfiles = currentProfiles.map((current) => current.id === profile.id ? result.data : current)
    renderProfiles(currentProfiles)
    if (body.linkedUserId !== undefined || body.unlinkUser === true) {
      void reloadMemberProfiles()
    } else {
      void loadProfiles()
    }
  }

  const publishProfile = async (profile: VenueStaffProfileDto) => {
    if (disposed || !directoryReady || mutatingProfiles.has(profile.id) || todayMutationProfileIds.has(profile.id)) return
    if (isOtherProfileSubtype(profile.subtype) && !profile.roleLabel?.trim()) {
      editingProfileId = profile.id
      isCreateFormOpen = false
      syncCreateFormVisibility()
      renderProfiles(currentProfiles)
      showToast('Укажите название роли')
      return
    }
    mutatingProfiles.add(profile.id)
    profilesReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    hideError()
    const result = await venuePublishStaffProfile(backendUrl, { venueId, profileId: profile.id }, deps)
    if (disposed) return
    mutatingProfiles.delete(profile.id)
    if (!result.ok) {
      renderProfiles(currentProfiles)
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error, false, profile.id)
        return
      }
      showError(result.error)
      return
    }
    showToast('Профиль опубликован')
    currentProfiles = currentProfiles.map((current) => current.id === profile.id ? result.data : current)
    renderProfiles(currentProfiles)
    void reloadMemberProfiles()
  }

  const hideProfile = async (profile: VenueStaffProfileDto) => {
    if (disposed || !directoryReady || mutatingProfiles.has(profile.id) || todayMutationProfileIds.has(profile.id)) return
    mutatingProfiles.add(profile.id)
    profilesReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    hideError()
    const result = await venueHideStaffProfile(backendUrl, { venueId, profileId: profile.id }, deps)
    if (disposed) return
    mutatingProfiles.delete(profile.id)
    if (!result.ok) {
      renderProfiles(currentProfiles)
      showError(result.error)
      return
    }
    showToast('Профиль скрыт')
    currentProfiles = currentProfiles.map((current) => current.id === profile.id ? result.data : current)
    renderProfiles(currentProfiles)
    void reloadMemberProfiles()
  }

  const updateTodayShift = async (
    profile: VenueStaffProfileDto,
    status: VenueStaffShiftStatus,
    isGuestVisible: boolean
  ) => {
    if (!moduleEnabled || moduleSettings?.todayStaffSource !== 'MANUAL' || !canManageProfileShifts) {
      showToast('Недостаточно прав')
      return
    }
    if (todayMutationProfileIds.has(profile.id) || mutatingProfiles.has(profile.id)) return
    todayMutationProfileIds.add(profile.id)
    profilesReadReady = false
    invalidateGuestVisibility()
    renderProfiles(currentProfiles)
    const result = await venueUpsertTodayStaffShift(
      backendUrl,
      { venueId, profileId: profile.id, body: { status, isGuestVisible } },
      deps
    )
    if (disposed) return
    todayMutationProfileIds.delete(profile.id)
    if (!result.ok) {
      renderProfiles(currentProfiles)
      showError(result.error)
      return
    }
    currentProfiles = currentProfiles.map((current) =>
      current.id === profile.id ? { ...current, todayShift: result.data.shift } : current
    )
    renderProfiles(currentProfiles)
    const profileCanBePublic =
      moduleSettings?.guestTeamVisible === true &&
      profile.isGuestVisible &&
      Boolean(profile.publishedAt) &&
      !profile.disabledAt
    const successMessage = !isGuestVisible
      ? 'Сотрудник скрыт из блока «Сегодня работают».'
      : profileCanBePublic
        ? 'Сотрудник отображается в блоке «Сегодня работают».'
        : moduleSettings?.guestTeamVisible === false
          ? 'Ручная отметка сохранена. Показ команды гостям отключён в настройках.'
          : 'Ручная отметка сохранена. Сначала опубликуйте карточку сотрудника.'
    showToast(successMessage)
    void loadProfiles()
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.refreshButton, 'click', () => {
    if (creatingProfile || mutatingProfiles.size || mutatingMembers.size) return
    reloadAll()
  }))
  disposables.push(on(refs.inviteButton, 'click', () => void createInvite()))
  disposables.push(on(refs.profileAddButton, 'click', () => {
    if (creatingProfile) return
    if (!canCreateProfiles || !directoryReady) {
      showToast('Дождитесь актуального списка сотрудников')
      return
    }
    keepCreateDraft()
    clearCreateFormFields()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      [],
      null,
      linkableProfileRoles
    )
    createProfileMode = { kind: 'DISPLAY_ONLY' }
    restoreCreateDraft()
    isCreateFormOpen = true
    editingProfileId = null
    syncCreateFormVisibility()
    renderProfiles(currentProfiles)
    refs.profileName.focus()
  }))
  disposables.push(on(refs.profileCreateButton, 'click', () => void createProfile()))
  const collapseCreateButton = refs.profileForm.querySelector<HTMLButtonElement>('[data-action="collapse-create"]')!
  disposables.push(on(collapseCreateButton, 'click', () => {
    keepCreateDraft()
    isCreateFormOpen = false
    syncCreateFormVisibility()
    refs.profileAddButton.focus()
  }))
  disposables.push(on(refs.profileCancelButton, 'click', resetCreateForm))
  disposables.push(on(refs.profileSubtype, 'change', () =>
    updateRoleLabelField(
      refs.profileRoleLabelField,
      refs.profileRoleLabel,
      refs.profileSubtype.value as VenueStaffProfileSubtype
    )
  ))
  const expiryTimer = window.setInterval(() => {
    const savedInvites = [...sessionInvites.values(), ...legacyInvites]
    const expiredSaved = savedInvites.some((saved) =>
      Date.parse(saved.response.expiresAt) <= Date.now() && !observedExpiredOrdinals.has(saved.ordinal)
    )
    if (expiredSaved || currentPendingInvites.some((invite) => Date.parse(invite.expiresAt) <= Date.now())) {
      currentPendingInvites = currentPendingInvites.filter((invite) => Date.parse(invite.expiresAt) > Date.now())
      renderPendingInvites()
    }
  }, 1000)
  disposables.push(() => window.clearInterval(expiryTimer))

  refs.inviteButton.disabled = !canInvite
  refs.inviteButton.title = canInvite ? '' : 'Недостаточно прав'
  populateLinkedUserSelect(
    refs.profileLinkedUser,
    staffMembers,
    null,
    linkableProfileRoles
  )
  syncCreateFormVisibility()
  if (!canCreateProfiles) {
    refs.profileName.disabled = true
    refs.profileSubtype.disabled = true
    refs.profileRoleLabel.disabled = true
    refs.profileLinkedUser.disabled = true
    refs.profileBio.disabled = true
    refs.profileTags.disabled = true
  }
  void loadStaff()
  void loadModuleSettings()
  if (moduleEnabled) void loadProfiles()
  void loadPendingInvites()

  return () => {
    disposed = true
    photoPanels.forEach(({ panel }) => panel.dispose())
    photoPanels.clear()
    sessionInvites.clear()
    legacyInvites.length = 0
    directoryReady = false
    staffMembers = []
    currentProfiles = []
    createProfileMode = null
    editingProfileId = null
    pendingOpenProfileId = null
    loadAbort?.abort()
    profileLoadAbort?.abort()
    pendingInviteLoadAbort?.abort()
    moduleSettingsLoadAbort?.abort()
    guestVisibilityLoadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
