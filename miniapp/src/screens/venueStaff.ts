import { REQUEST_ABORTED_CODE } from '../shared/api/abort'
import { clearSession, getAccessToken } from '../shared/api/auth'
import { normalizeErrorCode } from '../shared/api/errorMapping'
import {
  venueCreateInvite,
  venueCreateStaffProfile,
  venueGetStaff,
  venueGetStaffProfiles,
  venueHideStaffProfile,
  venuePublishStaffProfile,
  venueRemoveStaff,
  venueUpdateRole,
  venueUpdateStaffProfile,
  venueUpsertTodayStaffShift
} from '../shared/api/venueApi'
import type {
  VenueAccessDto,
  VenueStaffMemberDto,
  VenueStaffInviteResponse,
  VenueStaffProfileDto,
  VenueStaffProfileSubtype,
  VenueStaffProfileUpdateRequest,
  VenueStaffShiftStatus
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
  list: HTMLDivElement
  profileCard: HTMLElement
  profileAddButton: HTMLButtonElement
  profileForm: HTMLDivElement
  profileName: HTMLInputElement
  profileSubtype: HTMLSelectElement
  profileRoleLabelField: HTMLElement
  profileRoleLabel: HTMLInputElement
  profileLinkedUser: HTMLSelectElement
  profileBio: HTMLTextAreaElement
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

function buildStaffDom(root: HTMLDivElement): StaffRefs {
  const wrapper = el('div', { className: 'venue-staff' })
  const header = el('div', { className: 'card' })
  const title = el('h2', { text: 'Персонал' })
  const inviteRow = el('div', { className: 'venue-form-row' })
  const inviteRole = document.createElement('select')
  inviteRole.className = 'venue-select'
  inviteRole.appendChild(new Option('STAFF', 'STAFF'))
  inviteRole.appendChild(new Option('MANAGER', 'MANAGER'))
  inviteRole.appendChild(new Option('OWNER', 'OWNER'))
  const inviteButton = el('button', { text: 'Создать инвайт' }) as HTMLButtonElement
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

  append(header, title, inviteRow, inviteResult)

  const status = el('p', { className: 'status', text: '' })

  const error = el('div', { className: 'error-card' })
  error.hidden = true
  const errorTitle = el('h3')
  const errorMessage = el('p')
  const errorActions = el('div', { className: 'error-actions' })
  const errorDetails = el('div')
  append(error, errorTitle, errorMessage, errorActions, errorDetails)

  const list = el('div', { className: 'venue-staff-list' })

  const profileCard = el('section', { className: 'card venue-public-staff' })
  const profileTitle = el('h3', { text: 'Карточки сотрудников' })
  const profileDescription = el('p', {
    className: 'venue-order-sub',
    text: 'Создайте карточки сотрудников, которых гости увидят в карточке заведения. Например: кальянщики, официанты или администраторы.'
  })
  const privacyNote = el('p', { className: 'venue-order-sub', text: 'Гости видят только опубликованные карточки.' })
  const shiftNote = el('p', {
    className: 'venue-order-sub',
    text: 'Отметьте сотрудника «Сегодня на смене», чтобы он появился у гостей в блоке «Сегодня работают».'
  })
  const profileAddButton = el('button', { className: 'button-small', text: 'Добавить карточку сотрудника' }) as HTMLButtonElement
  const profileForm = el('div', { className: 'venue-profile-form' })
  profileForm.hidden = true
  const profileName = document.createElement('input')
  profileName.className = 'venue-input'
  profileName.placeholder = 'Например: Максим'
  profileName.maxLength = 120
  const profileSubtype = document.createElement('select')
  profileSubtype.className = 'venue-select'
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
  const profileBio = document.createElement('textarea')
  profileBio.className = 'venue-textarea'
  profileBio.placeholder = 'Например: Люблю крепкие миксы и помогаю подобрать вкус под настроение.'
  profileBio.maxLength = 1000
  profileBio.rows = 3
  const profileTags = document.createElement('input')
  profileTags.className = 'venue-input'
  profileTags.placeholder = 'Например: крепкие миксы, фруктовые чаши, авторские вкусы'
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
    renderProfileField('Имя на карточке', profileName, 'Так это имя увидят гости.'),
    renderProfileField('Тип сотрудника', profileSubtype),
    profileRoleLabelField,
    renderProfileField(
      'Привязать к сотруднику',
      profileLinkedUser,
      'Привязка нужна, чтобы сотрудник мог позже редактировать своё описание. Гостям эта связь не показывается.'
    ),
    photoPlaceholder,
    renderProfileField('Коротко о сотруднике', profileBio),
    renderProfileField(
      'Специализация',
      profileTags,
      'Можно указать через запятую. Это поможет гостям понять стиль сотрудника.'
    ),
    profileFormActions
  )
  const profileStatus = el('p', { className: 'status', text: '' })
  const profileList = el('div', { className: 'venue-staff-list venue-profile-list' })
  append(profileCard, profileTitle, profileDescription, privacyNote, shiftNote, profileAddButton, profileForm, profileStatus, profileList)

  append(wrapper, header, profileCard, status, error, list)
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
    list,
    profileCard,
    profileAddButton,
    profileForm,
    profileName,
    profileSubtype,
    profileRoleLabelField,
    profileRoleLabel,
    profileLinkedUser,
    profileBio,
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
  onUpdateRole: (member: VenueStaffMemberDto, role: VenueStaffMemberDto['role']) => void,
  onRemove: (member: VenueStaffMemberDto) => void
) {
  const row = el('div', { className: 'venue-staff-row' })
  const info = el('div', { className: 'venue-staff-info' })
  append(
    info,
    el('strong', { text: `User ${member.userId}` }),
    el('p', { className: 'venue-order-sub', text: `Роль: ${member.role}` })
  )

  const actions = el('div', { className: 'venue-staff-actions' })
  const roleSelect = document.createElement('select')
  roleSelect.className = 'venue-select'
  roleSelect.appendChild(new Option('OWNER', 'OWNER'))
  roleSelect.appendChild(new Option('MANAGER', 'MANAGER'))
  roleSelect.appendChild(new Option('STAFF', 'STAFF'))
  roleSelect.value = member.role
  const updateButton = el('button', { className: 'button-small', text: 'Обновить' }) as HTMLButtonElement
  const removeButton = el('button', { className: 'button-small button-secondary', text: 'Удалить' }) as HTMLButtonElement

  const disableManage = !canManageRoles || member.userId === currentUserId
  roleSelect.disabled = disableManage
  updateButton.disabled = disableManage
  removeButton.disabled = !canManageRoles || member.userId === currentUserId

  if (member.userId === currentUserId) {
    updateButton.title = 'Нельзя менять свою роль'
    removeButton.title = 'Нельзя удалить себя'
  } else if (!canManageRoles) {
    updateButton.title = 'Недостаточно прав'
    removeButton.title = 'Недостаточно прав'
  }

  updateButton.addEventListener('click', () => onUpdateRole(member, roleSelect.value as VenueStaffMemberDto['role']))
  removeButton.addEventListener('click', () => onRemove(member))

  append(actions, roleSelect, updateButton, removeButton)
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

function formatLinkedMemberOption(member: VenueStaffMemberDto, currentUserId: number): string {
  const self = member.userId === currentUserId ? ' · вы' : ''
  return `Сотрудник ${member.role}${self} · #${member.userId}`
}

function populateLinkedUserSelect(
  select: HTMLSelectElement,
  members: VenueStaffMemberDto[],
  currentUserId: number,
  selectedUserId: number | null | undefined
) {
  const selectedValue = selectedUserId ? String(selectedUserId) : ''
  select.replaceChildren(new Option('Не привязывать — просто карточка для гостей', ''))
  members.forEach((member) => {
    select.appendChild(new Option(formatLinkedMemberOption(member, currentUserId), String(member.userId)))
  })
  select.value = Array.from(select.options).some((option) => option.value === selectedValue) ? selectedValue : ''
}

function normalizeOptionalText(value: string): string | null {
  const trimmed = value.trim()
  return trimmed ? trimmed : null
}

function formatShiftLine(profile: VenueStaffProfileDto): string {
  const shift = profile.todayShift
  if (!shift) return 'Не на смене сегодня'
  const time =
    shift.startsAt || shift.endsAt
      ? ` · ${shift.startsAt ?? '—'}-${shift.endsAt ?? '—'}`
      : ''
  return `${formatShiftStatus(shift.status)}${time}`
}

function renderProfileRow(
  profile: VenueStaffProfileDto,
  access: VenueAccessDto,
  currentUserId: number,
  staffMembers: VenueStaffMemberDto[],
  isEditing: boolean,
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
  const isOwner = access.role === 'OWNER'
  const canEditOwn = access.role === 'STAFF' && profile.linkedUserId === currentUserId
  const canEdit = isOwner || canEditOwn
  const canManageShift = access.role === 'OWNER' || access.role === 'MANAGER'

  const row = el('div', { className: 'venue-staff-row venue-profile-row' })
  const info = el('div', { className: 'venue-staff-info' })
  append(
    info,
    el('strong', { text: profile.displayName }),
    el('p', {
      className: 'venue-order-sub',
      text: `${formatProfileRole(profile)} · ${formatShiftLine(profile)}`
    })
  )
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
    populateLinkedUserSelect(linkedSelect, staffMembers, currentUserId, profile.linkedUserId)
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
      if (isOwner && isOtherProfileSubtype(nextSubtype) && !nextRoleLabel) {
        showToast('Укажите название роли')
        roleLabelInput.focus()
        return
      }
      handlers.onSave(profile, {
        displayName: isOwner ? nameInput.value : undefined,
        roleLabel: isOwner ? (isOtherProfileSubtype(nextSubtype) ? nextRoleLabel : null) : undefined,
        subtype: isOwner ? nextSubtype : undefined,
        linkedUserId: isOwner ? linkedUserId : undefined,
        unlinkUser: isOwner ? linkedUserId === null : undefined,
        bio: bioInput.value,
        tags: splitTags(tagsInput.value)
      })
    })
    if (isOwner) {
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

  if (isOwner) {
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

  if (canManageShift) {
    const shiftHelp = el('p', {
      className: 'venue-order-sub',
      text: 'Отмеченные сотрудники появятся у гостей в блоке «Сегодня работают».'
    })
    const shiftActions = el('div', { className: 'venue-profile-shift-actions' })
    const activeButton = el('button', { className: 'button-small', text: 'Сегодня на смене' }) as HTMLButtonElement
    const inactiveButton = el('button', { className: 'button-small button-secondary', text: 'Не на смене сегодня' }) as HTMLButtonElement
    activeButton.addEventListener('click', () => handlers.onShift(profile, 'active', true))
    inactiveButton.addEventListener('click', () => handlers.onShift(profile, 'canceled', false))
    append(shiftActions, activeButton, inactiveButton)
    append(actions, shiftHelp, shiftActions)
  }

  append(row, info, actions)
  return row
}

export function renderVenueStaffScreen(options: VenueStaffOptions) {
  const { root, backendUrl, isDebug, venueId, access, currentUserId } = options
  if (!root) return () => undefined
  const refs = buildStaffDom(root)
  const deps = buildApiDeps(isDebug)

  let disposed = false
  let loadAbort: AbortController | null = null
  let profileLoadAbort: AbortController | null = null
  let loadSeq = 0
  let profileLoadSeq = 0
  let currentInvite: VenueStaffInviteResponse | null = null
  let staffMembers: VenueStaffMemberDto[] = []
  let currentProfiles: VenueStaffProfileDto[] = []
  let isCreateFormOpen = false
  let editingProfileId: number | null = null

  const canInvite = access.role !== 'STAFF'
  const canManageRoles = access.role === 'OWNER'
  const canCreateProfiles = access.role === 'OWNER'
  const canManageProfileShifts = access.role === 'OWNER' || access.role === 'MANAGER'

  const setStatus = (text: string) => {
    refs.status.textContent = text
  }

  const setProfileStatus = (text: string) => {
    refs.profileStatus.textContent = text
  }

  const syncCreateFormVisibility = () => {
    refs.profileForm.hidden = !isCreateFormOpen || !canCreateProfiles
    refs.profileAddButton.hidden = isCreateFormOpen || !canCreateProfiles
    updateRoleLabelField(
      refs.profileRoleLabelField,
      refs.profileRoleLabel,
      refs.profileSubtype.value as VenueStaffProfileSubtype
    )
  }

  const resetCreateForm = () => {
    refs.profileName.value = ''
    refs.profileSubtype.value = 'hookah_master'
    refs.profileRoleLabel.value = ''
    refs.profileLinkedUser.value = ''
    refs.profileBio.value = ''
    refs.profileTags.value = ''
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
    const venueName = currentInvite.venueName ?? access.venueName ?? `Venue ${venueId}`
    const expiresAt = formatInviteExpires(currentInvite.expiresAt)
    refs.inviteRoleLabel.textContent = role
    refs.inviteVenueName.textContent = venueName
    refs.inviteCopyStatus.textContent = ''
    refs.inviteCopyLinkButton.disabled = !deepLink
    refs.inviteShareButton.disabled = !deepLink
    refs.inviteFallbackDetails.open = false
    if (deepLink) {
      refs.inviteLinkField.value = deepLink
      const shareUrl = buildTelegramShareUrl(deepLink, venueName, role)
      refs.inviteShareButton.dataset.shareUrl = shareUrl
    } else {
      refs.inviteLinkField.value = 'Ссылка недоступна. Используйте запасную команду ниже.'
      delete refs.inviteShareButton.dataset.shareUrl
    }
    refs.inviteCommandField.value = fallbackCommand
    refs.inviteExpires.textContent = expiresAt
    refs.inviteHelper.textContent = deepLink
      ? buildInviteHelperText(role, venueName, expiresAt)
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
    const role = currentInvite?.role ?? refs.inviteRole.value
    const venueName = currentInvite?.venueName ?? access.venueName ?? `Venue ${venueId}`
    return buildTelegramShareUrl(link, venueName, role)
  }

  const renderStaff = (members: VenueStaffMemberDto[], currentUserId: number) => {
    refs.list.replaceChildren()
    staffMembers = members
    populateLinkedUserSelect(refs.profileLinkedUser, staffMembers, currentUserId, null)
    if (currentProfiles.length) {
      renderProfiles(currentProfiles)
    }
    if (!members.length) {
      refs.list.appendChild(el('p', { className: 'venue-empty', text: 'Сотрудники не найдены.' }))
      return
    }
    members.forEach((member) => {
      refs.list.appendChild(
        renderMemberRow(member, currentUserId, canManageRoles, (target, role) => void updateRole(target, role), (target) => void removeMember(target))
      )
    })
  }

  const renderProfiles = (profiles: VenueStaffProfileDto[]) => {
    refs.profileList.replaceChildren()
    if (!profiles.length) {
      refs.profileList.appendChild(el('p', { className: 'venue-empty', text: 'Профили не найдены.' }))
      return
    }
    profiles.forEach((profile) => {
      refs.profileList.appendChild(
        renderProfileRow(profile, access, currentUserId, staffMembers, editingProfileId === profile.id, {
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
        })
      )
    })
  }

  const loadStaff = async () => {
    if (access.role === 'STAFF') {
      staffMembers = []
      populateLinkedUserSelect(refs.profileLinkedUser, staffMembers, currentUserId, null)
      refs.list.replaceChildren(el('p', { className: 'venue-empty', text: 'Управление ролями недоступно.' }))
      setStatus('')
      return
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
    renderStaff(result.data.members, currentUserId)
    setStatus(`Обновлено: ${new Date().toLocaleTimeString()}`)
  }

  const loadProfiles = async () => {
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

  const reloadAll = () => {
    void loadStaff()
    void loadProfiles()
  }

  const createInvite = async () => {
    if (!canInvite) {
      showToast('Недостаточно прав')
      return
    }
    const role = refs.inviteRole.value as VenueStaffMemberDto['role']
    const result = await venueCreateInvite(backendUrl, { venueId, body: { role } }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    currentInvite = result.data
    renderInvite()
    showToast('Инвайт создан')
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
    void loadStaff()
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
    void loadStaff()
  }

  const createProfile = async () => {
    if (!canCreateProfiles) {
      showToast('Недостаточно прав')
      return
    }
    const linkedUserId = parseLinkedUserValue(refs.profileLinkedUser.value)
    if (Number.isNaN(linkedUserId)) {
      showToast('Некорректная привязка сотрудника')
      return
    }
    const displayName = refs.profileName.value.trim()
    if (!displayName) {
      showToast('Укажите имя')
      return
    }
    const subtype = refs.profileSubtype.value as VenueStaffProfileSubtype
    const roleLabel = normalizeOptionalText(refs.profileRoleLabel.value)
    if (isOtherProfileSubtype(subtype) && !roleLabel) {
      showToast('Укажите название роли')
      refs.profileRoleLabel.focus()
      return
    }
    const result = await venueCreateStaffProfile(
      backendUrl,
      {
        venueId,
        body: {
          displayName,
          roleLabel: isOtherProfileSubtype(subtype) ? roleLabel : null,
          subtype,
          linkedUserId,
          bio: normalizeOptionalText(refs.profileBio.value),
          tags: splitTags(refs.profileTags.value)
        }
      },
      deps
    )
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    resetCreateForm()
    showToast('Профиль создан')
    void loadProfiles()
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
      showError(result.error)
      return
    }
    showToast('Профиль обновлён')
    editingProfileId = null
    void loadProfiles()
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
      showError(result.error)
      return
    }
    showToast('Профиль опубликован')
    void loadProfiles()
  }

  const hideProfile = async (profile: VenueStaffProfileDto) => {
    const result = await venueHideStaffProfile(backendUrl, { venueId, profileId: profile.id }, deps)
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Профиль скрыт')
    void loadProfiles()
  }

  const updateTodayShift = async (
    profile: VenueStaffProfileDto,
    status: VenueStaffShiftStatus,
    isGuestVisible: boolean
  ) => {
    if (!canManageProfileShifts) {
      showToast('Недостаточно прав')
      return
    }
    const result = await venueUpsertTodayStaffShift(
      backendUrl,
      { venueId, profileId: profile.id, body: { status, isGuestVisible } },
      deps
    )
    if (disposed) return
    if (!result.ok) {
      showError(result.error)
      return
    }
    showToast('Смена обновлена')
    void loadProfiles()
  }

  const disposables: Array<() => void> = []
  disposables.push(on(refs.inviteButton, 'click', () => void createInvite()))
  disposables.push(on(refs.profileAddButton, 'click', () => {
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
  refs.profileCreateButton.disabled = !canCreateProfiles
  refs.profileCreateButton.title = canCreateProfiles ? '' : 'Недостаточно прав'
  populateLinkedUserSelect(refs.profileLinkedUser, staffMembers, currentUserId, null)
  syncCreateFormVisibility()
  if (!canCreateProfiles) {
    refs.profileName.disabled = true
    refs.profileSubtype.disabled = true
    refs.profileRoleLabel.disabled = true
    refs.profileLinkedUser.disabled = true
    refs.profileBio.disabled = true
    refs.profileTags.disabled = true
  }
  if (access.role === 'MANAGER') {
    refs.inviteRole.value = 'STAFF'
  }
  if (access.role === 'MANAGER') {
    const managerOptions = Array.from(refs.inviteRole.options)
    managerOptions.forEach((option) => {
      if (option.value !== 'STAFF') {
        option.disabled = true
      }
    })
  }

  void loadStaff()
  void loadProfiles()

  return () => {
    disposed = true
    loadAbort?.abort()
    profileLoadAbort?.abort()
    disposables.forEach((dispose) => dispose())
  }
}
