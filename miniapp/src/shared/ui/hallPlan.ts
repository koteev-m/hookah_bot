import { hallPlan, tableStateLabel, type Hall, type HallTable, type Layout } from '../api/seating'
import { append, el } from './dom'

export function renderHallPlan(root: HTMLElement, options: { base: string; venue: number; hall: Hall; tables: HallTable[]; guest?: boolean; edit?: boolean; selected?: number; onSelect: (table: HallTable) => void; onMove?: (table: HallTable, layout: Layout) => void }) {
  const { hall, tables } = options
  const controller = new AbortController()
  let disposed = false
  let url: string | null = null
  const controls = el('div', { className: 'button-row' })
  const zoom = document.createElement('input'); zoom.type = 'range'; zoom.min = '1'; zoom.max = '3'; zoom.step = '.1'; zoom.value = '1'; zoom.setAttribute('aria-label', 'Масштаб схемы')
  const fit = el('button', { text: 'Вписать схему', className: 'button-secondary' }) as HTMLButtonElement
  append(controls, zoom, fit)
  const scroll = el('div', { className: 'hall-plan-scroll' })
  const canvas = el('div', { className: 'hall-plan-canvas' })
  canvas.style.aspectRatio = `${hall.planWidth ?? 1} / ${hall.planHeight ?? 1}`
  const img = document.createElement('img'); img.alt = `Схема: ${hall.name}`; img.draggable = false
  canvas.append(img)
  for (const table of tables.filter(t => t.hallId === hall.id && t.layout)) {
    const marker = el('button', { className: `hall-marker hall-marker-${table.state.toLowerCase()}`, text: '' }) as HTMLButtonElement
    marker.type = 'button'
    const label = el('span', { text: `${table.number}\n${tableStateLabel(table.state)}` }); label.className = 'hall-marker-label'; marker.append(label)
    marker.setAttribute('aria-label', `Стол №${table.number}, ${table.capacity ?? 'неизвестно'} мест, ${tableStateLabel(table.state)}`)
    marker.setAttribute('aria-pressed', String(options.selected === table.id))
    if (options.guest && table.state !== 'AVAILABLE') marker.disabled = true
    let current = { ...table.layout! }
    const position = () => {
      marker.style.left = `${current.x * 100}%`; marker.style.top = `${current.y * 100}%`
      marker.style.width = `${current.width * 100}%`; marker.style.height = `${current.height * 100}%`
      marker.style.borderRadius = current.shape === 'ELLIPSE' ? '50%' : '8px'
      marker.style.transform = `translate(-50%, -50%) rotate(${current.rotation}deg)`
      label.style.transform = `rotate(${-current.rotation}deg)`
    }
    position()
    let drag: { x: number; y: number; moved: boolean } | null = null
    marker.addEventListener('pointerdown', e => {
      if (!options.edit) return
      options.onSelect(table)
      drag = { x: e.clientX, y: e.clientY, moved: false }; marker.setPointerCapture(e.pointerId)
    })
    marker.addEventListener('pointermove', e => {
      if (!drag) return
      if (Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) > 4) drag.moved = true
      if (!drag.moved) return
      const rect = canvas.getBoundingClientRect()
      current.x = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
      current.y = Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height))
      position(); options.onMove?.(table, current)
    })
    marker.addEventListener('pointerup', () => { drag = null })
    marker.addEventListener('pointercancel', () => { drag = null })
    marker.addEventListener('click', () => options.onSelect(table))
    canvas.append(marker)
  }
  zoom.addEventListener('input', () => { canvas.style.width = `${Number(zoom.value) * 100}%` })
  fit.addEventListener('click', () => { zoom.value = '1'; canvas.style.width = '100%'; scroll.scrollTo(0, 0) })
  append(scroll, canvas); append(root, controls, scroll)
  void hallPlan(options.base, options.venue, hall, Boolean(options.guest), controller.signal).then(result => {
    if (disposed) return
    if (!result.ok) { img.alt = 'Не удалось загрузить схему. Используйте список или обновите данные.'; return }
    url = URL.createObjectURL(result.data.blob); img.src = url
  })
  return () => { disposed = true; controller.abort(); if (url) URL.revokeObjectURL(url) }
}
