// The faint artwork tint behind the mini player. The artwork's dominant colour (`r, g, b`) is only ever allowed to be dark:
// it is scaled down until its relative luminance is at most `max`, and the bar paints it at a low alpha over its own
// surface, so text on the bar keeps WCAG AA contrast whatever the artwork looks like.

const channel = (value: number) => {
  const v = value / 255
  return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
}

/** WCAG relative luminance of an `r, g, b` triple (0-255). */
export function luminance(r: number, g: number, b: number) {
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/** Parses "r, g, b"; undefined for anything else. */
export function parseRgb(value: string | undefined): [number, number, number] | undefined {
  if (!value) return undefined
  const parts = value.split(',').map((part) => Number(part.trim()))
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part) || part < 0 || part > 255)) return undefined
  return [parts[0], parts[1], parts[2]]
}

/** The colour scaled (keeping its hue) until its luminance is at most `max`; unchanged when already dark enough. */
export function darkenedTint(value: string | undefined, max = 0.12): string | undefined {
  const rgb = parseRgb(value)
  if (!rgb) return undefined
  if (luminance(...rgb) <= max) return rgb.map(Math.round).join(', ')
  let low = 0
  let high = 1
  for (let step = 0; step < 14; step++) {
    const mid = (low + high) / 2
    if (luminance(rgb[0] * mid, rgb[1] * mid, rgb[2] * mid) > max) high = mid
    else low = mid
  }
  return rgb.map((part) => Math.round(part * low)).join(', ')
}
