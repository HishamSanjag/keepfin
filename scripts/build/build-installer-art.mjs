// Reproducible Windows installer artwork (option A, "Graphite"), drawn from the app icon's geometry:
//   node scripts/build/build-installer-art.mjs
// Writes assets/installer/sidebar.bmp (164x314, welcome and finish pages) and header.bmp (150x57, inner pages).
// NSIS wants 24-bit BMP files at exactly these sizes; they add a few hundred KB to the installer and nothing at run time.
import fs from 'node:fs/promises'
import path from 'node:path'
import {Resvg} from '@resvg/resvg-js'

import {installerMark, mainVariantId} from './build-icon-variants.mjs'

// The main icon variant (assets/icons/variants/index.json) drives the art; --variant, --accent and --out allow a dry run.
const args = process.argv.slice(2)
const option = (name) => {
  const at = args.indexOf(`--${name}`)
  return at >= 0 ? args[at + 1] : undefined
}
const variant = option('variant') ?? mainVariantId()
const base = path.resolve(option('out') ?? path.join(import.meta.dirname, '..', '..'))
const out = path.join(base, 'assets', 'installer')
const {colours, size: markSize, inner, logo, emblem, wordmark} = installerMark(variant, option('accent'))
const graphite = colours.ink
const tile = colours.tile
const cobalt = colours.accent
const cobaltLight = colours.accentLight

// The icon's shapes on a slightly lighter tile so the mark reads on the graphite ground.
const mark = (x, y, size, background = tile) => `
  <g transform="translate(${x} ${y}) scale(${size / markSize})">
${inner.replace('__TILE__', background)}
  </g>`

const font = `font-family="Segoe UI, Segoe UI Variable, Arial, sans-serif"`

// A design with a stacked logo (mark over wordmark) shows it whole; the others show their mark, the name and the tagline.
const sidebarArt = logo
  ? `<image href="${logo}" x="2" y="64" width="160" height="159"/>`
  : `${mark(18, 22, 72)}
  <text x="18" y="252" ${font} font-size="22" font-weight="600" fill="#FFFFFF">Keepfin</text>
  <rect x="18" y="262" width="28" height="3" fill="${cobalt}"/>`
const sidebar = `<svg xmlns="http://www.w3.org/2000/svg" width="164" height="314" viewBox="0 0 164 314">
  <rect width="164" height="314" fill="${graphite}"/>
  ${sidebarArt}
  <text x="18" y="286" ${font} font-size="11" fill="${cobaltLight}">Keep what you download</text>
</svg>`

// Inner pages: the header band stays white like the rest of the dialog; the logo, or the icon, sits at its right end.
const headerArt = emblem
  ? `<image href="${emblem}" x="6" y="5" width="58" height="47"/>
  <image href="${wordmark}" x="64" y="13" width="82" height="30"/>`
  : mark(100, 10.5, 36, graphite)
const header = `<svg xmlns="http://www.w3.org/2000/svg" width="150" height="57" viewBox="0 0 150 57">
  <rect width="150" height="57" fill="#FFFFFF"/>
  ${headerArt}
</svg>`

/** A 24-bit bottom-up BMP from RGBA pixels (opaque artwork, so alpha is ignored). */
function bmp(width, height, rgba) {
  const rowBytes = Math.ceil((width * 3) / 4) * 4
  const size = 54 + rowBytes * height
  const file = Buffer.alloc(size)
  file.write('BM', 0, 'ascii')
  file.writeUInt32LE(size, 2)
  file.writeUInt32LE(54, 10)
  file.writeUInt32LE(40, 14)
  file.writeInt32LE(width, 18)
  file.writeInt32LE(height, 22)
  file.writeUInt16LE(1, 26)
  file.writeUInt16LE(24, 28)
  file.writeUInt32LE(rowBytes * height, 34)
  file.writeInt32LE(2835, 38)
  file.writeInt32LE(2835, 42)
  for (let y = 0; y < height; y++) {
    const row = 54 + (height - 1 - y) * rowBytes
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4
      file[row + x * 3] = rgba[i + 2]
      file[row + x * 3 + 1] = rgba[i + 1]
      file[row + x * 3 + 2] = rgba[i]
    }
  }
  return file
}

// The installer is DPI aware (installer.nsh), so Windows no longer stretches its window; it loads the art for the screen's
// scale at run time. sidebar.bmp/header.bmp are the 100% art (and what NSIS embeds by default); -<scale> files are the rest.
export const scales = [100, 125, 150, 175, 200, 250, 300]

async function render(name, svg, width, height, scale) {
  const image = new Resvg(svg, {fitTo: {mode: 'zoom', value: scale / 100}, font: {loadSystemFonts: true, defaultFontFamily: 'Segoe UI'}}).render()
  const w = Math.round((width * scale) / 100)
  const h = Math.round((height * scale) / 100)
  if (Math.abs(image.width - w) > 1 || Math.abs(image.height - h) > 1) throw new Error(`${name}: rendered ${image.width}x${image.height}, expected ${w}x${h}`)
  await fs.writeFile(path.join(out, name), bmp(image.width, image.height, image.pixels))
}

await fs.mkdir(out, {recursive: true})
for (const scale of scales) {
  const suffix = scale === 100 ? '' : `-${scale}`
  await render(`sidebar${suffix}.bmp`, sidebar, 164, 314, scale)
  await render(`header${suffix}.bmp`, header, 150, 57, scale)
}
console.log(`Wrote sidebar and header art at ${scales.join(', ')}% for "${variant}" in ${out}`)
