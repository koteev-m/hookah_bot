import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateInvite,
  venueCreateStaffProfile,
  venueCreateStaffProfileFromMember,
  venueGetPendingStaffInvites,
  venueGetStaff,
  venueGetStaffModuleSettings,
  venueGetStaffProfiles,
  venueHideStaffProfile,
  venuePublishStaffProfile,
  venueRemoveStaff,
  venueRevokeStaffInvite,
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
}

type StaffRefs = {
  status: HTMLParagraphElement
  error: HTMLDivElement
  errorTitle: HTMLHeadingElement
  errorMessage: HTMLParagraphElement
  errorActions: HTMLDivElement
  errorDetails: HTMLDivElement
  inviteRole: HTMLSelectElement
  inviteButton: HTMLButtonElement
  inviteResult: HTMLDivElement
  inviteRoleLabel: HTMLSpanElement
  inviteVenueName: HTMLSpanElement
  inviteLinkField: HTMLTextAreaElement
  inviteCopyLinkButton: HTMLButtonElement
  inviteShareButton: HTMLButtonElement
  inviteFallbackDetails: HTMLDetailsElement
  inviteCommandField: HTMLTextAreaElement
  inviteCopyCommandButton: HTMLButtonElement
  inviteExpires: HTMLSpanElement
  inviteHelper: HTMLParagraphElement
  inviteCopyStatus: HTMLParagraphElement
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
  const accessTitle = el('h3', { text: 'Доступ сотрудников' })
  const accessDescription = el('p', {
    className: 'venue-order-sub',
    text: 'Управляйте текущими доступами и приглашениями в это заведение.'
  })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteRole = document.createElement('select')
  inviteRole.className = 'venue-select'
  inviteRole.setAttribute('aria-label', 'Роль приглашения')
  inviteRole.appendChild(new Option('Сотрудник', 'STAFF'))
  if (access.role === 'OWNER') {
    inviteRole.appendChild(new Option('Менеджер', 'MANAGER'))
  }
  const inviteButton = el('button', { text: 'Добавить сотрудника' }) as HTMLButtonElement
  append(inviteRow, inviteRole, inviteButton)

  const inviteResult = el('div', { className: 'venue-invite-result' })
  inviteResult.hidden = true
  const inviteResultTitle = el('h3', { text: 'Приглашение создано' })
  const inviteRoleLabel = el('span')
  const inviteVenueName = el('span')
  const inviteExpires = el('span')
  const inviteMeta = el('div', { className: 'venue-invite-meta' })
  append(
    inviteMeta,
    el('p', { text: 'Роль:' }),
    inviteRoleLabel,
    el('p', { text: 'Заведение:' }),
    inviteVenueName,
    el('p', { text: 'Действует до:' }),
    inviteExpires
  )

  const inviteLinkLabel = el('label', { className: 'field-label', text: 'Ссылка для сотрудника' })
  inviteLinkLabel.htmlFor = 'venue-staff-invite-link'
  const inviteLinkField = document.createElement('textarea')
  inviteLinkField.id = 'venue-staff-invite-link'
  inviteLinkField.className = 'venue-invite-field'
  inviteLinkField.readOnly = true
  inviteLinkField.rows = 2
  const inviteCopyLinkButton = el('button', {
    className: 'button-small button-secondary',
    text: '📋 Скопировать ссылку'
  }) as HTMLButtonElement
  const inviteShareButton = el('button', {
    className: 'button-small button-secondary',
    text: '📤 Поделиться в Telegram'
  }) as HTMLButtonElement
  const inviteLinkActions = el('div', { className: 'venue-invite-actions' })
  append(inviteLinkActions, inviteCopyLinkButton, inviteShareButton)

  const inviteHelper = el('p', { className: 'venue-invite-help', text: '' })

  const inviteFallbackDetails = document.createElement('details')
  inviteFallbackDetails.className = 'venue-invite-fallback'
  const inviteFallbackSummary = document.createElement('summary')
  inviteFallbackSummary.textContent = 'Если ссылка не открылась'
  const inviteFallbackHelp = el('p', {
    className: 'venue-invite-help',
    text: 'Скопируйте команду и отправьте её сотруднику вручную.'
  })

  const inviteCommandField = document.createElement('textarea')
  inviteCommandField.id = 'venue-staff-invite-command'
  inviteCommandField.className = 'venue-invite-field'
  inviteCommandField.readOnly = true
  inviteCommandField.rows = 2
  inviteCommandField.setAttribute('aria-label', 'Команда, если ссылка не открылась')
  const inviteCopyCommandButton = el('button', {
    className: 'button-small button-secondary',
    text: '📋 Скопировать команду'
  }) as HTMLButtonElement
  append(inviteFallbackDetails, inviteFallbackSummary, inviteFallbackHelp, inviteCommandField, inviteCopyCommandButton)
  const inviteCopyStatus = el('p', { className: 'venue-invite-copy-status', text: '' })
  inviteCopyStatus.setAttribute('aria-live', 'polite')
  append(
    inviteResult,
    inviteResultTitle,
    inviteMeta,
    inviteLinkLabel,
    inviteLinkField,
    inviteLinkActions,
    inviteHelper,
    inviteFallbackDetails,
    inviteCopyStatus
  )

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-staff-list' })

  const pendingInviteTitle = el('h3', { text: 'Ожидают принятия' })
  const pendingInviteStatus = el('p', { className: 'status', text: '' }) as HTMLParagraphElement
  pendingInviteStatus.setAttribute('aria-live', 'polite')
  const pendingInviteList = el('div', { className: 'venue-staff-list venue-pending-invite-list' }) as HTMLDivElement

  append(
    header,
    title,
    accessTitle,
    accessDescription,
    inviteRow,
    inviteResult,
    pendingInviteTitle,
    pendingInviteStatus,
    pendingInviteList,
    status,
    error,
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
    text: 'Создайте карточки сотрудников, которых гости увидят в карточке заведения. Например: кальянщики, официанты или администраторы.'
  })
  const privacyNote = el('p', { className: 'venue-order-sub', text: 'Гости видят только опубликованные карточки.' })
  const shiftNote = el('p', {
    className: 'venue-order-sub',
    text: 'Отметьте сотрудника «Сегодня на смене», чтобы он появился у гостей в блоке «Сегодня работают».'
  })
  const protectedNote = el('p', {
    className: 'venue-order-sub',
    text: 'Карточками владельцев и других менеджеров управляет владелец.'
  })
  protectedNote.hidden = access.role !== 'MANAGER'
  const profileAddButton = el('button', { className: 'button-small', text: 'Добавить карточку сотрудника' }) as HTMLButtonElement
  const profileForm = el('div', { className: 'venue-profile-form' })
  profileForm.hidden = true
  const profileName = document.createElement('input')
  profileName.className = 'venue-input'
  profileName.placeholder = 'Например: Максим'
  profileName.maxLength = 120
  const profileNameField = renderProfileField('Имя на карточке', profileName, 'Так это имя увидят гости.')
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
    text: 'Фото сотрудника — позже'
  })
  const profileCreateButton = el('button', { text: 'Создать профиль' }) as HTMLButtonElement
  const profileCancelButton = el('button', { className: 'button-secondary', text: 'Отмена' }) as HTMLButtonElement
  const profileFormActions = el('div', { className: 'venue-profile-form-actions' })
  append(profileFormActions, profileCreateButton, profileCancelButton)
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
  const profileList = el('div', { className: 'venue-staff-list venue-profile-list' })
  append(
    profileEnabledContent,
    profileDescription,
    privacyNote,
    shiftNote,
    protectedNote,
    profileAddButton,
    profileForm,
    profileStatus,
    profileList
  )
  append(profileCard, profileTitle, profileDisabledPanel, profileEnabledContent)

  append(wrapper, header, profileCard)
  root.replaceChildren(wrapper)

  return {
    status,
    error,
    errorTitle,
    errorMessage,
    errorActions,
    errorDetails,
    inviteRole,
    inviteButton,
    inviteResult,
    inviteRoleLabel,
    inviteVenueName,
    inviteLinkField,
    inviteCopyLinkButton,
    inviteShareButton,
    inviteFallbackDetails,
    inviteCommandField,
    inviteCopyCommandButton,
    inviteExpires,
    inviteHelper,
    inviteCopyStatus,
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

function buildInviteHelperText(role: string, venueName: string, expiresAt: string): string {
  return `Отправьте эту ссылку сотруднику. Он откроет её в Telegram и получит роль ${role} в заведении «${venueName}». Ссылка одноразовая и действует до ${expiresAt}.`
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
  const info = el('div', { className: 'venue-staff-info' })
  const identityMeta = el('p', { className: 'venue-staff-identity-meta' })
  append(
    identityMeta,
    el('span', { text: formatMemberUsername(member) }),
    document.createTextNode(' · '),
    el('span', { className: 'venue-staff-role-badge', text: formatStaffRole(member.role) })
  )
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

  if (canManageRoles) {
    const roleSelect = document.createElement('select')
    roleSelect.className = 'venue-select'
    roleSelect.setAttribute('aria-label', `Роль: ${member.displayName}`)
    roleSelect.appendChild(new Option('Владелец', 'OWNER'))
    roleSelect.appendChild(new Option('Менеджер', 'MANAGER'))
    roleSelect.appendChild(new Option('Сотрудник', 'STAFF'))
    roleSelect.value = member.role
    const updateButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
    const removeButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement

    const isCurrentMember = member.userId === currentUserId
    roleSelect.disabled = isCurrentMember
    updateButton.disabled = isCurrentMember
    removeButton.disabled = isCurrentMember
    if (isCurrentMember) {
      updateButton.title = 'Нельзя менять свою роль'
      removeButton.title = 'Нельзя удалить себя'
    }

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
      return 'Карточка не создана'
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

function renderProfileRow(
  profile: VenueStaffProfileDto,
  access: VenueAccessDto,
  moduleSettings: VenueStaffModuleSettingsDto | null,
  staffMembers: VenueStaffMemberDto[],
  directoryReady: boolean,
  isEditing: boolean,
  todayMutationPending: boolean,
  handlers: {
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
  append(
    info,
    el('strong', { text: profile.displayName }),
    el('p', {
      className: 'venue-order-sub',
      text: formatProfileRole(profile)
    })
  )
  info.appendChild(
    el('p', {
      className: 'venue-order-sub',
      text: `По графику: ${formatScheduledShiftLine(profile)}`
    })
  )
  const manualGuestVisible =
    profile.todayShift?.isGuestVisible === true &&
    (profile.todayShift.status === 'active' || profile.todayShift.status === 'scheduled')
  if (moduleSettings?.todayStaffSource === 'MANUAL') {
    info.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: moduleSettings.guestTeamVisible
          ? `Для гостей: ${manualGuestVisible ? 'включено' : 'выключено'} вручную`
          : `Для гостей: ${manualGuestVisible ? 'включено вручную, но показ команды отключён в настройках' : 'выключено вручную'}`
      })
    )
  }
  if (profile.tags?.length) {
    info.appendChild(el('p', { className: 'venue-order-sub', text: profile.tags.join(', ') }))
  }
  if (profile.bio) {
    info.appendChild(el('p', { className: 'venue-profile-bio', text: profile.bio }))
  }
  const visibility =
    profile.isGuestVisible && profile.publishedAt && !profile.disabledAt
      ? 'Опубликован — виден гостям'
      : 'Скрыт — виден только в кабинете'
  info.appendChild(el('p', { className: 'venue-order-sub', text: visibility }))
  if (
    access.role === 'MANAGER' &&
    profile.linkageClass === 'PROTECTED' &&
    !profile.isSelf
  ) {
    info.appendChild(
      el('p', {
        className: 'venue-order-sub',
        text: 'Только просмотр — карточкой управляет владелец.'
      })
    )
  }

  const actions = el('div', { className: 'venue-staff-actions venue-profile-actions' })

  if (canEdit && isEditing) {
    const nameInput = document.createElement('input')
    nameInput.className = 'venue-input'
    nameInput.value = profile.displayName
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
    subtypeSelect.value = profile.subtype || 'other'
    const roleLabelInput = document.createElement('input')
    roleLabelInput.className = 'venue-input'
    roleLabelInput.value = profile.roleLabel ?? ''
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
      profile.linkedUserId,
      access.role === 'OWNER' ? undefined : new Set<VenueStaffMemberDto['role']>(['STAFF'])
    )
    const bioInput = document.createElement('textarea')
    bioInput.className = 'venue-textarea'
    bioInput.value = profile.bio ?? ''
    bioInput.placeholder = 'Например: Люблю крепкие миксы и помогаю подобрать вкус под настроение.'
    bioInput.rows = 3
    bioInput.maxLength = 1000
    const tagsInput = document.createElement('input')
    tagsInput.className = 'venue-input'
    tagsInput.value = profile.tags?.join(', ') ?? ''
    tagsInput.placeholder = 'Например: крепкие миксы, фруктовые чаши, авторские вкусы'
    const saveButton = el('button', { className: 'button-small', text: 'Сохранить' }) as HTMLButtonElement
    const cancelButton = el('button', { className: 'button-small button-secondary', text: 'Отмена' }) as HTMLButtonElement
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
      text: profile.isGuestVisible && profile.publishedAt && !profile.disabledAt ? 'Скрыть' : 'Опубликовать'
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

  let disposed = false
  let loadAbort: AbortController | null = null
  let profileLoadAbort: AbortController | null = null
  let pendingInviteLoadAbort: AbortController | null = null
  let moduleSettingsLoadAbort: AbortController | null = null
  let loadSeq = 0
  let profileLoadSeq = 0
  let pendingInviteLoadSeq = 0
  let currentInvite: VenueStaffInviteResponse | null = null
  let currentPendingInvites: VenueStaffPendingInviteDto[] = []
  const revokingInviteHandles = new Set<string>()
  let staffMembers: VenueStaffMemberDto[] = []
  let directoryReady = false
  let currentProfiles: VenueStaffProfileDto[] = []
  let isCreateFormOpen = false
  let createProfileMode: CreateProfileMode | null = null
  let editingProfileId: number | null = null
  let pendingOpenProfileId: number | null = null
  let moduleSettings: VenueStaffModuleSettingsDto | null = null
  const todayMutationProfileIds = new Set<number>()

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

  const canRevokeInvite = (invite: VenueStaffPendingInviteDto): boolean =>
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
    refs.profileName.readOnly = isFromMember
    refs.profileLinkedUserField.hidden = !isFromMember
    refs.profileLinkedUser.disabled = true
    refs.profilePhotoPlaceholder.hidden = isFromMember
    refs.profileBioField.hidden = isFromMember
    refs.profileTagsField.hidden = isFromMember
    refs.profileCreateButton.textContent = isFromMember ? 'Создать карточку' : 'Создать профиль'
    refs.profileCreateButton.disabled = !formUsable
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

  const resetCreateForm = () => {
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

  const renderInvite = () => {
    if (!currentInvite) {
      refs.inviteResult.hidden = true
      return
    }
    const startPayload = currentInvite.startPayload ?? `staff_invite_${currentInvite.inviteCode}`
    const fallbackCommand = currentInvite.fallbackCommand ?? `/start ${startPayload}`
    const deepLink = currentInvite.deepLink?.trim() || null
    const role = currentInvite.role ?? refs.inviteRole.value
    const roleLabel = formatStaffRole(role)
    const venueName = currentInvite.venueName ?? access.venueName ?? `Venue ${venueId}`
    const expiresAt = formatInviteExpires(currentInvite.expiresAt)
    refs.inviteRoleLabel.textContent = roleLabel
    refs.inviteVenueName.textContent = venueName
    refs.inviteCopyStatus.textContent = ''
    refs.inviteCopyLinkButton.disabled = !deepLink
    refs.inviteShareButton.disabled = !deepLink
    refs.inviteFallbackDetails.open = false
    if (deepLink) {
      refs.inviteLinkField.value = deepLink
      const shareUrl = buildTelegramShareUrl(deepLink, venueName, roleLabel)
      refs.inviteShareButton.dataset.shareUrl = shareUrl
    } else {
      refs.inviteLinkField.value = 'Ссылка недоступна. Используйте запасную команду ниже.'
      delete refs.inviteShareButton.dataset.shareUrl
    }
    refs.inviteCommandField.value = fallbackCommand
    refs.inviteExpires.textContent = expiresAt
    refs.inviteHelper.textContent = deepLink
      ? buildInviteHelperText(roleLabel, venueName, expiresAt)
      : 'Ссылка недоступна. Скопируйте команду ниже и отправьте её сотруднику вручную.'
    refs.inviteResult.hidden = false
  }

  const copyInviteText = async (
    text: string,
    field: HTMLTextAreaElement,
    successMessage: string,
    fallbackMessage: string
  ) => {
    refs.inviteCopyStatus.textContent = ''
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(text)
        refs.inviteCopyStatus.textContent = successMessage
        showToast(successMessage)
        return
      } catch {
        // Fall through to visible manual selection below.
      }
    }
    selectVisibleField(field)
    refs.inviteCopyStatus.textContent = fallbackMessage
    showToast(fallbackMessage)
  }

  const openUrl = (url: string) => {
    const telegram = getTelegramContext()
    if (telegram.webApp?.openTelegramLink) {
      try {
        telegram.webApp.openTelegramLink(url)
        return
      } catch {
        // Fall through to the browser fallback below.
      }
    }
    const opened = window.open(url, '_blank', 'noopener,noreferrer')
    if (!opened) {
      window.location.href = url
    }
  }

  const currentInviteDeepLink = () => currentInvite?.deepLink?.trim() || null

  const currentInviteShareUrl = () => {
    const link = currentInviteDeepLink()
    if (!link) return null
    const role = formatStaffRole(currentInvite?.role ?? refs.inviteRole.value)
    const venueName = currentInvite?.venueName ?? access.venueName ?? `Venue ${venueId}`
    return buildTelegramShareUrl(link, venueName, role)
  }

  const renderPendingInvites = () => {
    refs.pendingInviteList.replaceChildren()
    const visibleInvites =
      access.role === 'MANAGER'
        ? currentPendingInvites.filter((invite) => invite.role === 'STAFF')
        : currentPendingInvites
    if (!visibleInvites.length) {
      refs.pendingInviteList.appendChild(
        el('p', { className: 'venue-empty', text: 'Ожидающих приглашений нет.' })
      )
      return
    }
    visibleInvites.forEach((invite) => {
      const row = el('div', { className: 'venue-staff-row venue-pending-invite-row' })
      const info = el('div', { className: 'venue-staff-info' })
      append(
        info,
        el('strong', { text: formatStaffRole(invite.role) }),
        el('p', {
          className: 'venue-order-sub',
          text: 'Статус: ожидает принятия'
        }),
        el('p', {
          className: 'venue-order-sub',
          text: `Создано: ${formatInviteExpires(invite.createdAt)}`
        }),
        el('p', {
          className: 'venue-order-sub',
          text: `Действует до: ${formatInviteExpires(invite.expiresAt)}`
        })
      )
      row.appendChild(info)
      if (canRevokeInvite(invite)) {
        const revokeButton = el('button', {
          className: 'button-small button-secondary',
          text: 'Отозвать приглашение'
        }) as HTMLButtonElement
        revokeButton.disabled = revokingInviteHandles.has(invite.handle)
        revokeButton.addEventListener('click', () => void revokePendingInvite(invite))
        row.appendChild(revokeButton)
      }
      refs.pendingInviteList.appendChild(row)
    })
  }

  const renderStaff = (members: VenueStaffMemberDto[], currentUserId: number) => {
    refs.list.replaceChildren()
    staffMembers = members.filter(
      (member) => member.active && (access.role === 'OWNER' || member.role === 'STAFF')
    )
    syncCreateFormVisibility()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      staffMembers,
      null,
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
    refs.profileList.replaceChildren()
    if (!profiles.length) {
      refs.profileList.appendChild(el('p', { className: 'venue-empty', text: 'Профили не найдены.' }))
      return
    }
    profiles.forEach((profile) => {
      refs.profileList.appendChild(
        renderProfileRow(
          profile,
          access,
          moduleSettings,
          staffMembers,
          directoryReady,
          editingProfileId === profile.id,
          todayMutationProfileIds.has(profile.id),
          {
          onSave: (target, draft) => void saveProfile(target, draft),
          onEdit: (target) => {
            editingProfileId = target.id
            isCreateFormOpen = false
            syncCreateFormVisibility()
            renderProfiles(currentProfiles)
          },
          onCancelEdit: () => {
            editingProfileId = null
            renderProfiles(currentProfiles)
          },
          onPublish: (target) => void publishProfile(target),
          onHide: (target) => void hideProfile(target),
          onShift: (target, status, isGuestVisible) => void updateTodayShift(target, status, isGuestVisible)
          }
        )
      )
    })
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
    editingProfileId = null
    isCreateFormOpen = true
    syncCreateFormVisibility()
    renderProfiles(currentProfiles)
    refs.profileSubtype.focus()
    refs.profileForm.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }

  const openProfileForMember = (member: VenueStaffMemberDto) => {
    if (!moduleEnabled) {
      showToast('Карточки команды отключены в настройках')
      return
    }
    const profileId = member.linkedStaffProfileId
    if (member.profileLinkState !== 'LINKED' || profileId == null) {
      showToast('Связанная карточка недоступна')
      return
    }
    resetCreateForm()
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
    editingProfileId = null
    pendingOpenProfileId = null
    resetCreateForm()
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

  const loadProfiles = async () => {
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
    renderProfiles(result.data.profiles)
    setProfileStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const loadModuleSettings = async () => {
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
      refs.pendingInviteStatus.textContent = ''
      showError(result.error)
      return
    }
    currentPendingInvites = result.data.invites
    renderPendingInvites()
    refs.pendingInviteStatus.textContent = ''
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

  const handleProfileLinkConflict = async (error: ApiErrorInfo) => {
    const details = parseStaffProfileLinkConflictDetails(error.details)
    resetCreateForm()
    editingProfileId = null
    hideError()
    await reloadMemberProfiles()
    if (disposed) return
    if (
      directoryReady &&
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

  const revokePendingInvite = async (invite: VenueStaffPendingInviteDto) => {
    if (!canRevokeInvite(invite) || revokingInviteHandles.has(invite.handle)) {
      showToast('Недостаточно прав')
      return
    }
    revokingInviteHandles.add(invite.handle)
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
    void loadPendingInvites()
  }

  const createInvite = async () => {
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
    const result = await venueCreateInvite(backendUrl, { venueId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    currentInvite = result.data
    renderInvite()
    showToast('Приглашение создано')
    void loadPendingInvites()
  }

  const updateRole = async (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => {
    if (!canManageRoles) return
    if (member.userId === currentUserId) {
      showToast('Нельзя менять свою роль')
      return
    }
    const result = await venueUpdateRole(backendUrl, { venueId, userId: member.userId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Роль обновлена')
    void reloadMemberProfiles()
  }

  const removeMember = async (member: VenueStaffMemberDto) => {
    if (!canManageRoles) return
    if (member.userId === currentUserId) {
      showToast('Нельзя удалить себя')
      return
    }
    if (!window.confirm('Удалить участника?')) return
    const result = await venueRemoveStaff(backendUrl, { venueId, userId: member.userId }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Участник удалён')
    void reloadMemberProfiles()
  }

  const createProfile = async () => {
    if (!canCreateProfiles || !directoryReady || !createProfileMode) {
      showToast('Недостаточно прав')
      return
    }
    const mode = createProfileMode
    const displayName = refs.profileName.value.trim()
    if (mode.kind === 'DISPLAY_ONLY' && !displayName) {
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
    const result =
      mode.kind === 'FROM_MEMBER'
        ? await venueCreateStaffProfileFromMember(
          backendUrl,
          {
            venueId,
            body: {
              userId: mode.userId,
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
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error)
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
    const body: VenueStaffProfileUpdateRequest = {
      displayName: draft.displayName,
      roleLabel: draft.roleLabel === undefined ? undefined : draft.roleLabel === null ? null : normalizeOptionalText(draft.roleLabel),
      subtype: draft.subtype,
      linkedUserId: draft.linkedUserId,
      unlinkUser: draft.unlinkUser,
      bio: draft.bio === undefined ? undefined : draft.bio === null ? null : normalizeOptionalText(draft.bio),
      tags: draft.tags ?? undefined
    }
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
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error)
        return
      }
      showError(result.error)
      return
    }
    showToast('Профиль обновлён')
    editingProfileId = null
    if (body.linkedUserId !== undefined || body.unlinkUser === true) {
      void reloadMemberProfiles()
    } else {
      void loadProfiles()
    }
  }

  const publishProfile = async (profile: VenueStaffProfileDto) => {
    if (isOtherProfileSubtype(profile.subtype) && !profile.roleLabel?.trim()) {
      editingProfileId = profile.id
      isCreateFormOpen = false
      syncCreateFormVisibility()
      renderProfiles(currentProfiles)
      showToast('Укажите название роли')
      return
    }
    const result = await venuePublishStaffProfile(backendUrl, { venueId, profileId: profile.id }, deps)
    if (disposed) return
    if (!result.ok) {
      if (result.error.code === ApiErrorCodes.STAFF_PROFILE_LINK_CONFLICT) {
        await handleProfileLinkConflict(result.error)
        return
      }
      showError(result.error)
      return
    }
    showToast('Профиль опубликован')
    void reloadMemberProfiles()
  }

  const hideProfile = async (profile: VenueStaffProfileDto) => {
    const result = await venueHideStaffProfile(backendUrl, { venueId, profileId: profile.id }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Профиль скрыт')
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
    if (todayMutationProfileIds.has(profile.id)) return
    todayMutationProfileIds.add(profile.id)
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
  disposables.push(on(refs.inviteButton, 'click', () => void createInvite()))
  disposables.push(on(refs.profileAddButton, 'click', () => {
    if (!canCreateProfiles || !directoryReady) {
      showToast('Дождитесь актуального списка сотрудников')
      return
    }
    clearCreateFormFields()
    populateLinkedUserSelect(
      refs.profileLinkedUser,
      [],
      null,
      linkableProfileRoles
    )
    createProfileMode = { kind: 'DISPLAY_ONLY' }
    isCreateFormOpen = true
    editingProfileId = null
    syncCreateFormVisibility()
    renderProfiles(currentProfiles)
    refs.profileName.focus()
  }))
  disposables.push(on(refs.profileCreateButton, 'click', () => void createProfile()))
  disposables.push(on(refs.profileCancelButton, 'click', resetCreateForm))
  disposables.push(on(refs.profileSubtype, 'change', () =>
    updateRoleLabelField(
      refs.profileRoleLabelField,
      refs.profileRoleLabel,
      refs.profileSubtype.value as VenueStaffProfileSubtype
    )
  ))
  disposables.push(
    on(refs.inviteCopyLinkButton, 'click', () => {
      const link = currentInviteDeepLink()
      if (!link) {
        showToast('Ссылка недоступна')
        return
      }
      void copyInviteText(
        link,
        refs.inviteLinkField,
        'Ссылка скопирована',
        'Не удалось скопировать автоматически. Ссылка выделена ниже.'
      )
    })
  )
  disposables.push(
    on(refs.inviteShareButton, 'click', () => {
      const shareUrl = currentInviteShareUrl()
      if (!shareUrl) {
        showToast('Ссылка недоступна')
        return
      }
      openUrl(shareUrl)
    })
  )
  disposables.push(
    on(refs.inviteCopyCommandButton, 'click', () => {
      if (!currentInvite) return
      const startPayload = currentInvite.startPayload ?? `staff_invite_${currentInvite.inviteCode}`
      void copyInviteText(
        currentInvite.fallbackCommand ?? `/start ${startPayload}`,
        refs.inviteCommandField,
        'Команда скопирована',
        'Не удалось скопировать автоматически. Команда выделена ниже.'
      )
    })
  )

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
    disposables.forEach((dispose) => dispose())
  }
}
