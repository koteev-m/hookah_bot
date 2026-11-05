import { getTelegramContext } from '../telegram'

type Color = [number, number, number]

function parseColor(value: string | undefined): Color | null {
  if (!value || !/^#(?:[\da-f]{3}|[\da-f]{6})$/i.test(value)) return null
  const hex = value.length === 4 ? value.slice(1).split('').map(char => char + char).join('') : value.slice(1)
  return [0, 2, 4].map(index => Number.parseInt(hex.slice(index, index + 2), 16)) as Color
}

function luminance(color: Color): number {
  const values = color.map(channel => {
    const value = channel / 255
    return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4
  })
  return values[0] * .2126 + values[1] * .7152 + values[2] * .0722
}

function contrast(a: Color, b: Color): number {
  const x = luminance(a)
  const y = luminance(b)
  return (Math.max(x, y) + .05) / (Math.min(x, y) + .05)
}

function mix(a: Color, b: Color, amount: number): Color {
  return a.map((channel, index) => Math.round(channel * (1 - amount) + b[index] * amount)) as Color
}

function readable(color: Color, backgrounds: Color[], minimum: number): Color {
  const score = (candidate: Color) => Math.min(...backgrounds.map(background => contrast(candidate, background)))
  const dark: Color = [0, 0, 0]
  const light: Color = [255, 255, 255]
  const fallback = score(dark) > score(light) ? dark : light
  for (let step = 0; step <= 20; step++) {
    const candidate = mix(color, fallback, step / 20)
    if (score(candidate) >= minimum) return candidate
  }
  return fallback
}

// Keep Telegram's palette when readable, and repair conflicting custom theme pairs locally.
export function bindAppTheme(element: HTMLElement): () => void {
  const webApp = getTelegramContext().webApp
  const system = window.matchMedia('(prefers-color-scheme: dark)')
  const update = () => {
    const params = webApp?.themeParams
    const dark = webApp?.colorScheme ? webApp.colorScheme === 'dark' : system.matches
    const bg = parseColor(params?.bg_color) ?? parseColor(dark ? '#151a18' : '#f5f6f3')!
    const text = readable(parseColor(params?.text_color) ?? parseColor(dark ? '#edf3ed' : '#202925')!, [bg], 4.5)
    let surface = parseColor(params?.secondary_bg_color) ?? parseColor(dark ? '#1e2521' : '#ffffff')!
    if (contrast(text, surface) < 4.5) {
      const original = surface
      for (let step = 1; step <= 20; step++) {
        surface = mix(original, bg, step / 20)
        if (contrast(text, surface) >= 4.5) break
      }
    }
    const tinted = mix(surface, text, .055)
    const soft = contrast(text, tinted) >= 4.5 ? tinted : surface
    const backgrounds = [bg, surface, soft]
    const muted = readable(parseColor(params?.hint_color) ?? mix(surface, text, .7), backgrounds, 4.5)
    const accent = readable(parseColor(params?.button_color) ?? parseColor(dark ? '#a6c9bc' : '#24584f')!, backgrounds, 4.5)
    const onAccent = readable(parseColor(params?.button_text_color) ?? [255, 255, 255], [accent], 4.5)
    const danger = readable(parseColor(dark ? '#f2afa4' : '#a32d26')!, backgrounds, 4.5)
    const onDanger = readable([255, 255, 255], [danger], 4.5)
    const success = readable(parseColor(dark ? '#a4c9b0' : '#296240')!, backgrounds, 4.5)
    const warning = readable(parseColor(dark ? '#e3c18c' : '#85551b')!, backgrounds, 4.5)
    const values = {
      bg, surface, text, muted, accent, 'on-accent': onAccent, soft,
      border: mix(surface, text, .14), 'control-border': readable(mix(surface, text, .45), backgrounds, 3),
      danger, 'on-danger': onDanger, success, warning,
      'danger-soft': soft, 'success-soft': soft, 'warning-soft': soft
    }
    for (const [name, value] of Object.entries(values)) {
      element.style.setProperty(`--app-${name}`, `rgb(${value.join(', ')})`)
    }
    element.dataset.colorScheme = luminance(bg) < .18 ? 'dark' : 'light'
    element.style.colorScheme = element.dataset.colorScheme
  }
  update()
  const viewport = window.visualViewport
  const keyboard = () => {
    const active = document.activeElement
    const editing = active instanceof HTMLElement && active.matches('textarea, input:not([type=button]):not([type=checkbox]):not([type=radio]):not([type=file])')
    const open = Boolean(editing && viewport && viewport.scale === 1 && window.innerHeight - viewport.height > 120)
    element.toggleAttribute('data-keyboard-open', open)
    if (open) active?.scrollIntoView({ block: 'nearest' })
  }
  viewport?.addEventListener('resize', keyboard)
  document.addEventListener('focusin', keyboard)
  document.addEventListener('focusout', keyboard)
  system.addEventListener('change', update)
  let listening = false
  if (webApp?.onEvent && webApp.offEvent) {
    try { webApp.onEvent('themeChanged', update); listening = true } catch { /* initial palette remains available */ }
  }
  return () => {
    viewport?.removeEventListener('resize', keyboard)
    document.removeEventListener('focusin', keyboard)
    document.removeEventListener('focusout', keyboard)
    system.removeEventListener('change', update)
    if (listening) webApp?.offEvent?.('themeChanged', update)
  }
}
