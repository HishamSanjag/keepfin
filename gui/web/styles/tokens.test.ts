import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {luminance} from '../player/tint.js'

// WCAG contrast of the design tokens (tokens.css), per theme: text on every surface, and the accent pairs.
const css = fs.readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), 'tokens.css'), 'utf8')
const block = (selector: string) => {
  const start = css.indexOf(selector)
  return css.slice(css.indexOf('{', start), css.indexOf('}', start))
}
const vars = (selector: string) => {
  const out: Record<string, string> = {}
  for (const match of block(selector).matchAll(/--([a-z0-9-]+):\s*(#[0-9a-fA-F]{6})\s*;/g)) out[match[1]] = match[2]
  return out
}
const rgb = (hex: string): [number, number, number] => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number]
const ratio = (a: string, b: string) => {
  const [x, y] = [luminance(...rgb(a)), luminance(...rgb(b))]
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05)
}
const themes = {dark: vars(":root[data-theme='dark']"), light: vars(":root[data-theme='light']")}

for (const [name, v] of Object.entries(themes)) {
  test(`${name}: text and accent text keep 4.5:1 on canvas, surface and raised`, () => {
    for (const surface of ['canvas', 'surface', 'raised', 'rail'])
      for (const text of ['text', 'text-2', 'text-3', 'accent-text']) assert.ok(ratio(v[text], v[surface]) >= 4.5, `${text} on ${surface}: ${ratio(v[text], v[surface]).toFixed(2)}`)
  })
  test(`${name}: field borders (inputs, selects, segmented controls) keep about 3:1 on the surface they sit on`, () => {
    for (const surface of ['surface']) assert.ok(ratio(v['field-border'], v[surface]) >= 2.9, `field-border on ${surface}: ${ratio(v['field-border'], v[surface]).toFixed(2)}`)
  })
  test(`${name}: the accent works as text, selection and progress, and filled controls carry readable text`, () => {
    assert.ok(ratio(v.accent, v.canvas) >= 4.5, `accent on canvas ${ratio(v.accent, v.canvas).toFixed(2)}`)
    assert.ok(ratio(v.accent, v.surface) >= 4.5, `accent on surface ${ratio(v.accent, v.surface).toFixed(2)}`)
    assert.ok(ratio(v['on-accent'], v.accent) >= 4.5, `on-accent on accent ${ratio(v['on-accent'], v.accent).toFixed(2)}`)
  })
}
