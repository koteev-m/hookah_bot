import { append, el } from './dom'

function labeledControl<T extends HTMLInputElement | HTMLTextAreaElement>(
  id: string,
  labelText: string,
  control: T
) {
  const field = el('div', { className: 'ownership-field' })
  const label = el('label', { className: 'field-label', text: labelText }) as HTMLLabelElement
  control.id = id
  label.htmlFor = id
  append(field, label, control)
  return field
}

export function venueApplicationFields(prefix: string, commentLabel: string) {
  const formGrid = el('div', { className: 'venue-form-grid' })
  const venueName = document.createElement('input')
  venueName.className = 'venue-input'
  venueName.placeholder = 'Например, «Северный дым»'
  venueName.maxLength = 200
  venueName.required = true
  venueName.autocomplete = 'organization'
  const city = document.createElement('input')
  city.className = 'venue-input'
  city.placeholder = 'Например, Казань'
  city.maxLength = 120
  city.required = true
  city.autocomplete = 'address-level2'
  const contact = document.createElement('input')
  contact.className = 'venue-input'
  contact.placeholder = 'Телефон или Telegram username'
  contact.maxLength = 200
  contact.required = true
  const comment = document.createElement('textarea')
  comment.className = 'venue-input'
  comment.placeholder = 'Дополнительная информация для платформы'
  comment.maxLength = 500
  append(
    formGrid,
    labeledControl(`${prefix}-venue-name`, 'Название заведения', venueName),
    labeledControl(`${prefix}-city`, 'Город', city),
    labeledControl(`${prefix}-contact`, 'Контакт для связи', contact),
    labeledControl(`${prefix}-comment`, commentLabel, comment)
  )
  return { formGrid, venueName, city, contact, comment }
}
