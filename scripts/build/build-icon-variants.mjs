// Recolourable icon variants (assets/icons/variants/index.json + one SVG per variant).
//
// A variant is vector (<path> fills in its SVG) or raster (an <image> of approved artwork, e.g. Ink Tide). A raster
// variant may add "small" (a second SVG used for every size under 48 px: a crop that stays legible), "silhouette" (a
// vector SVG whose shape draws the one-colour Android notification and themed icons, at "silhouetteScale") and "logo" (stacked logo PNGs,
// paths relative to assets/branding).
//
// Variant SVGs use only the colour tokens listed in index.json (ink, accent, accentLight, paper). This module
// substitutes them, so a variant can be drawn once and shipped in any accent colour. Nothing here depends on how many
// shapes a variant has: every <path> in the file is used, in order, and the first <rect> is the full-bleed background.
//
//   import {renderIcon, listVariants, outputsFor} from './build-icon-variants.mjs'
//   renderIcon('drop', '#C2410C', 256)            -> PNG buffer (rounded tile)
//   renderIcon('drop', '#C2410C', 256, {mono: true}) -> white symbol on transparent
import fs from 'node:fs'
import path from 'node:path'
import {Resvg} from '@resvg/resvg-js'

const root = path.join(import.meta.dirname, '..', '..')
const brandDir = path.join(root, 'assets', 'branding')
const dir = path.join(root, 'assets', 'icons', 'variants')
const TOKEN_KEYS = ['ink', 'accent', 'accentLight', 'paper']

const readIndex = () => JSON.parse(fs.readFileSync(path.join(dir, 'index.json'), 'utf8'))
const hex = (value) => {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(value).trim())
  if (!m) throw new Error(`Not a #RRGGBB colour: ${value}`)
  return `#${m[1].toUpperCase()}`
}
/** Mix a colour toward white (amount 0..1). */
export function mixWhite(colour, amount) {
  const n = parseInt(hex(colour).slice(1), 16)
  const channel = (shift) => {
    const value = (n >> shift) & 255
    return Math.round(value + (255 - value) * amount)
  }
  return `#${[16, 8, 0].map((s) => channel(s).toString(16).padStart(2, '0')).join('').toUpperCase()}`
}

/** All variants with display names, tokens and the main id. The apps can read index.json directly too. */
export function listVariants() {
  const index = readIndex()
  return index.variants.map((entry) => {
    const tokens = {...index.tokens, ...entry.tokens}
    for (const key of TOKEN_KEYS) tokens[key] = hex(tokens[key])
    return {
      id: entry.id,
      name: entry.name,
      file: entry.file ?? `${entry.id}.svg`,
      adaptiveScale: entry.adaptiveScale ?? 1,
      silhouette: entry.silhouette,
      silhouetteScale: entry.silhouetteScale ?? 1,
      logo: entry.logo,
      fixedColours: entry.fixedColours === true,
      tokens,
      main: entry.id === index.main,
    }
  })
}
export const mainVariantId = () => readIndex().main
/** Accent colours the app icons are pre-rendered for (index.json "accents"); the first is the main accent. */
export const listAccents = () => (readIndex().accents ?? []).map(hex)
export function getVariant(id) {
  const variant = listVariants().find((v) => v.id === id)
  if (!variant) throw new Error(`Unknown icon variant "${id}"`)
  return variant
}

/** The colours to paint with: the variant's own tokens, with the accent replaced when one is given (unless the variant sets fixedColours). */
export function palette(variant, accent, accentLight) {
  const t = {...variant.tokens}
  if (variant.fixedColours) accent = accentLight = undefined // a fixed-colour design is never recoloured
  if (accent && hex(accent) !== t.accent) {
    t.accent = hex(accent)
    t.accentLight = accentLight ? hex(accentLight) : mixWhite(t.accent, 0.4)
  } else if (accentLight) t.accentLight = hex(accentLight)
  t.tile = variant.tokens.tile ?? mixWhite(t.ink, 0.06)
  return t
}

/** Make an SVG self-contained: every <image> that points at a file (relative to the variants folder) becomes a data URI. */
function inlineImages(svg) {
  return svg.replace(/(<image\b[^>]*?\shref=")([^":]+)(")/g, (_, head, file, tail) => {
    const png = fs.readFileSync(path.resolve(dir, file))
    return `${head}data:image/png;base64,${png.toString('base64')}${tail}`
  })
}
/** Removes XML comments, repeating until none is left (one pass can join the pieces of a nested one). */
function withoutComments(text) {
  for (let before = ''; before !== text; ) [before, text] = [text, text.replace(/\s*<!--[\s\S]*?-->/g, '')]
  return text
}
function themed(variant, file, accent, accentLight) {
  const source = withoutComments(fs.readFileSync(path.join(dir, file), 'utf8'))
  const to = palette(variant, accent, accentLight)
  const map = new Map(TOKEN_KEYS.map((key) => [variant.tokens[key], to[key]]))
  return inlineImages(source.replace(/#[0-9a-fA-F]{6}\b/g, (colour) => map.get(colour.toUpperCase()) ?? colour))
}
/** The variant's SVG text with colours substituted in one pass (comments removed, images inlined). */
export function themedSvg(id, accent, accentLight) {
  const variant = getVariant(id)
  return themed(variant, variant.file, accent, accentLight)
}
/** The SVG that draws the one-colour Android shapes (notification, themed icon): the variant's silhouette file, else the variant itself. */
function silhouetteOf(id, accent) {
  const variant = getVariant(id)
  if (variant.silhouette) return {svg: themed(variant, variant.silhouette, accent), scale: variant.silhouetteScale}
  return {svg: themedSvg(id, accent), scale: variant.adaptiveScale}
}
const isVector = (svg) => /<path\s/.test(svg)

export function viewBoxSize(svg) {
  const m = /viewBox="0 0 ([\d.]+) ([\d.]+)"/.exec(svg)
  if (!m) throw new Error('Icon SVG needs viewBox="0 0 N N"')
  return Number(m[1])
}
const rectPattern = /<rect[^>]+\/>/
/** Give a square full-bleed background the rounded tile used where nothing masks the icon (web, Windows, legacy Android). */
export function withTile(svg) {
  const background = rectPattern.exec(svg)?.[0]
  if (!background || /\srx=/.test(background)) return svg
  const radius = Math.round(viewBoxSize(svg) * (112 / 512))
  return svg.replace(background, background.replace('<rect ', `<rect rx="${radius}" `))
}
/** Full-bleed round artwork for legacy round launchers. */
export function withCircle(svg, ink) {
  const half = viewBoxSize(svg) / 2
  return svg.replace(rectPattern, `<circle cx="${half}" cy="${half}" r="${half}" fill="${ink}"/>`)
}
/** The symbol alone (no title, no background), as used beside a wordmark. */
export const markSvg = (svg) => svg.replace(/\s*<title>.*?<\/title>/, '').replace(/\s*<rect[^>]+\/>/, '')
/** Every symbol shape (all <path> elements) as attribute maps. */
export function shapes(svg) {
  const found = [...svg.matchAll(/<path\s+([^>]+)\/>/g)].map((match) =>
    Object.fromEntries([...match[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(([, key, value]) => [key, value])),
  )
  if (!found.length) throw new Error('Icon variant has no symbol paths')
  return found
}

const pngOf = (svg, size) =>
  new Resvg(svg, {fitTo: {mode: 'width', value: size}, font: {loadSystemFonts: false}}).render().asPng()

/** A PNG centred on a transparent square canvas at its own pixel size (no resampling). */
function centredPng(png, size) {
  const width = png.readUInt32BE(16), height = png.readUInt32BE(20)
  const x = Math.floor((size - width) / 2), y = Math.floor((size - height) / 2)
  return pngOf(`<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 ${size} ${size}"><image x="${x}" y="${y}" width="${width}" height="${height}" href="data:image/png;base64,${png.toString('base64')}"/></svg>`, size)
}

/** A PNG without the given margin on each side, at its own pixel size (no resampling). */
function trimmedPng(file, margin) {
  const png = fs.readFileSync(file)
  const width = png.readUInt32BE(16), height = png.readUInt32BE(20)
  const w = width - margin * 2, h = height - margin * 2
  return pngOf(`<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}"><image x="${-margin}" y="${-margin}" width="${width}" height="${height}" href="data:image/png;base64,${png.toString('base64')}"/></svg>`, w)
}

/**
 * Render a variant to PNG. Default: the rounded tile. {mono: true} gives the symbol in white on transparent (the
 * Android themed-icon layer); {round: true} gives the circular legacy launcher artwork; {plain: true} the unrounded tile.
 */
export function renderIcon(id, accent, size, options = {}) {
  const svg = themedSvg(id, accent)
  if (options.mono) {
    const white = markSvg(svg).replace(/#[0-9a-fA-F]{6}\b/g, '#FFFFFF')
    return pngOf(white, size)
  }
  if (options.round) return pngOf(withCircle(svg, palette(getVariant(id), accent).ink), size)
  return pngOf(options.plain ? svg : withTile(svg), size)
}

/** The symbol alone, scaled about the centre of its canvas, as a transparent PNG (the adaptive foreground of a raster design). */
function foregroundPng(svg, scale, px) {
  const size = viewBoxSize(svg)
  const centre = size / 2
  const inner = markSvg(svg).replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '')
  const group = `<g transform="translate(${centre} ${centre}) scale(${scale}) translate(-${centre} -${centre})">${inner}</g>`
  return pngOf(`<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}">${group}</svg>`, px)
}

function nativePaths(svg, monochrome) {
  const colour = (value) => (monochrome ? '#FFFFFF' : value)
  return shapes(svg)
    .map((attrs) => {
      const fill =
        attrs.fill && attrs.fill !== 'none'
          ? `android:fillColor="${colour(attrs.fill)}"`
          : 'android:fillColor="@android:color/transparent"'
      const type = attrs['fill-rule'] === 'evenodd' ? ' android:fillType="evenOdd"' : ''
      const stroke =
        attrs.stroke && attrs.stroke !== 'none'
          ? ` android:strokeColor="${colour(attrs.stroke)}" android:strokeWidth="${attrs['stroke-width']}" android:strokeLineCap="round" android:strokeLineJoin="round"`
          : ''
      return `    <path android:pathData="${attrs.d}" ${fill}${type}${stroke}/>`
    })
    .join('\n')
}
function vector(svg, scale, adaptive, monochrome = false, dp = adaptive ? 108 : 24) {
  const size = viewBoxSize(svg)
  const centre = size / 2
  const group = scale !== 1
  return `<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="${dp}dp" android:height="${dp}dp" android:viewportWidth="${size}" android:viewportHeight="${size}">
${group ? `  <group android:scaleX="${scale}" android:scaleY="${scale}" android:pivotX="${centre}" android:pivotY="${centre}">\n` : ''}${nativePaths(svg, monochrome)}
${group ? '  </group>\n' : ''}</vector>\n`
}
const adaptiveXml = (themed) => `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/icon_background"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
${themed ? '    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>\n' : ''}</adaptive-icon>\n`

const ICO_SIZES = [16, 24, 32, 48, 64, 128, 256]
/** The rounded-tile SVG of a variant. */
const tiledArt = (id, accent) => withTile(themedSvg(id, accent))

/** ICO container (PNG entries) for the given sizes. */
function icoOf(art, sizes) {
  const images = sizes.map((size) => pngOf(art, size))
  const header = Buffer.alloc(6 + sizes.length * 16)
  header.writeUInt16LE(1, 2)
  header.writeUInt16LE(sizes.length, 4)
  let offset = header.length
  sizes.forEach((size, index) => {
    const entry = 6 + index * 16
    header[entry] = size === 256 ? 0 : size
    header[entry + 1] = header[entry]
    header.writeUInt16LE(1, entry + 4)
    header.writeUInt16LE(32, entry + 6)
    header.writeUInt32LE(images[index].length, entry + 8)
    header.writeUInt32LE(offset, entry + 12)
    offset += images[index].length
  })
  return Buffer.concat([header, ...images])
}

/**
 * The adaptive launcher layers of one design under one resource name: the foreground (vector for a vector design, a
 * transparent PNG for a raster one; the other kind is listed as null so a stale file is removed) and the monochrome layer.
 */
function launcherLayers(variant, accent, name) {
  const res = 'android/app/src/main/res'
  const source = themedSvg(variant.id, accent)
  const out = new Map()
  if (isVector(source)) {
    out.set(`${res}/drawable/${name}_foreground.xml`, vector(source, variant.adaptiveScale, true))
    out.set(`${res}/drawable-nodpi/${name}_foreground.png`, null)
  } else {
    out.set(`${res}/drawable-nodpi/${name}_foreground.png`, foregroundPng(source, variant.adaptiveScale, 432))
    out.set(`${res}/drawable/${name}_foreground.xml`, null)
  }
  const silhouette = silhouetteOf(variant.id, accent)
  out.set(`${res}/drawable/${name}_monochrome.xml`, vector(silhouette.svg, silhouette.scale, true, true))
  return out
}

/**
 * Every shipped icon file for one variant and accent, as a Map of repo-relative path -> Buffer|string (null = the file must not exist).
 * build-icons.mjs writes this map for the main variant; a picker can call it for any other.
 */
export function outputsFor(id, accent) {
  const variant = getVariant(id)
  const colours = palette(variant, accent)
  const source = themedSvg(id, accent)
  const tiled = tiledArt(id, accent)
  const out = new Map()
  const png = (file, size) => out.set(file, pngOf(tiled, size))

  out.set('assets/icons/icon.svg', tiled)
  out.set('assets/icons/mark.svg', markSvg(tiled))
  png('assets/icons/icon.png', 512)
  out.set('gui/web/public/icon.svg', tiled) // the browser tab icon
  png('gui/web/public/icon.png', 192)
  png('gui/web/public/icon-512.png', 512)
  png('gui/web/public/apple-touch-icon.png', 180)
  png('gui/web/public/favicon-16.png', 16)
  png('gui/web/public/favicon-32.png', 32)
  const ico = icoOf(tiled, ICO_SIZES)
  out.set('assets/icons/icon.ico', ico)
  out.set('gui/web/public/favicon.ico', ico)

  const res = 'android/app/src/main/res'
  out.set(`${res}/values/icon_colors.xml`, `<resources><color name="icon_background">${colours.ink}</color></resources>\n`)
  const round = withCircle(source, colours.ink)
  for (const [density, size] of Object.entries({mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192})) {
    out.set(`${res}/mipmap-${density}/ic_launcher.png`, pngOf(tiled, size))
    out.set(`${res}/mipmap-${density}/ic_launcher_round.png`, pngOf(round, size))
  }
  for (const [file, data] of launcherLayers(variant, accent, 'ic_launcher')) out.set(file, data)
  // Status-bar icon: the white symbol alone, enlarged to fill the 24dp canvas (the symbol fills a circle of radius 400 of 1024).
  out.set(`${res}/drawable/ic_notification.xml`, vector(silhouetteOf(id, accent).svg, 1.2, false, true))
  // In-app brand pictures (the React Mark and Logo components, the Android header marks): the small mark and the stacked logo.
  const logo = variant.logo ? fs.readFileSync(path.join(brandDir, variant.logo.stacked)) : pngOf(tiled, 512)
  const logoLight = variant.logo ? fs.readFileSync(path.join(brandDir, variant.logo.stackedLight)) : logo
  // The full emblem and the lettering alone, without the clear space around the files: the Windows sidebar shows them
  // side by side, Android's empty pages show the emblem, and the small mark is the emblem on a square canvas.
  const emblem = variant.logo ? trimmedPng(path.join(brandDir, variant.logo.emblem), 40) : pngOf(tiled, 512)
  const mark = variant.logo ? centredPng(emblem, 555) : pngOf(tiled, 96)
  out.set('gui/web/public/brand/mark.png', mark)
  out.set('gui/web/public/brand/logo.png', logo)
  out.set('gui/web/public/brand/logo-light.png', logoLight)
  out.set(`${res}/drawable-nodpi/brand_mark.png`, mark)
  out.set(`${res}/drawable-nodpi/brand_logo.png`, logo)
  out.set(`${res}/drawable-nodpi/brand_logo_light.png`, logoLight)
  out.set('gui/web/public/brand/emblem.png', emblem)
  out.set('gui/web/public/brand/wordmark.png', variant.logo ? trimmedPng(path.join(brandDir, variant.logo.wordmark), 32) : mark)
  out.set('gui/web/public/brand/wordmark-light.png', variant.logo ? trimmedPng(path.join(brandDir, variant.logo.wordmarkLight), 32) : mark)
  out.set(`${res}/drawable-nodpi/brand_emblem.png`, emblem)
  // Android 12+ splash: the emblem at its native pixels inside the 288dp icon canvas (the visible circle is its middle
  // two thirds), and the wordmark in the 200x80dp branding slot below it, in ivory for dark mode and ink for light.
  const splash = 1152
  out.set(`${res}/drawable-nodpi/splash_icon.png`, variant.logo ? centredPng(fs.readFileSync(path.join(brandDir, variant.logo.emblem)), splash) : pngOf(tiled, splash))
  out.set(`${res}/drawable-nodpi/splash_brand.png`, variant.logo ? fs.readFileSync(path.join(brandDir, variant.logo.wordmark)) : mark)
  out.set(`${res}/drawable-nodpi/splash_brand_light.png`, variant.logo ? fs.readFileSync(path.join(brandDir, variant.logo.wordmarkLight)) : mark)
  for (const name of ['ic_launcher', 'ic_launcher_round']) {
    out.set(`${res}/mipmap-anydpi-v26/${name}.xml`, adaptiveXml(false))
    out.set(`${res}/mipmap-anydpi-v33/${name}.xml`, adaptiveXml(true))
  }
  return out
}

/** Pieces the installer artwork needs: colours, the small symbol markup (tile rect + shapes) on its own viewBox, and the stacked logo (a data URI, or null when the design has none). */
export function installerMark(id, accent) {
  const variant = getVariant(id)
  const colours = palette(variant, accent)
  const svg = tiledArt(id, accent)
  const size = viewBoxSize(svg)
  const inner = svg.replace(/^[\s\S]*?<svg[^>]*>/, '').replace(/<\/svg>\s*$/, '').replace(/\s*<title>.*?<\/title>/, '')
  const dataUri = (png) => `data:image/png;base64,${png.toString('base64')}`
  const logo = variant.logo ? dataUri(fs.readFileSync(path.join(brandDir, variant.logo.stacked))) : null
  // for the white header: the emblem and the ink lettering, without their clear space
  const emblem = variant.logo ? dataUri(trimmedPng(path.join(brandDir, variant.logo.emblem), 40)) : null
  const wordmark = variant.logo ? dataUri(trimmedPng(path.join(brandDir, variant.logo.wordmarkLight), 32)) : null
  return {colours, size, logo, emblem, wordmark, inner: inner.replace(/<rect([^>]*)fill="[^"]*"/, `<rect$1fill="__TILE__"`)}
}

/**
 * The per-variant, per-accent files the Windows app icon picker uses: a 16..256 ICO for the window, taskbar and
 * shortcuts, and a 128 px PNG for the picker. Names are <id>-<ACCENTHEX>.<ext> (hex without #, upper case).
 */
export function appIconOutputs() {
  const out = new Map()
  for (const variant of listVariants()) {
    for (const accent of listAccents()) {
      const name = `${variant.id}-${accent.slice(1)}`
      const art = tiledArt(variant.id, accent)
      out.set(`resources/app-icons/${name}.ico`, icoOf(art, ICO_SIZES))
      out.set(`gui/web/public/app-icons/${name}.png`, pngOf(art, 128))
    }
  }
  return out
}
/* ---------- Android launcher icons for the picker (every variant x accent) ---------- */

const ANDROID_RES = 'android/app/src/main/res'
const ANDROID_MANIFEST = 'android/app/src/main/AndroidManifest.xml'
const ALIAS_BEGIN = '<!-- app-icons:begin (generated by scripts/build/build-icons.mjs from assets/icons/variants/index.json; do not edit) -->'
const ALIAS_END = '<!-- app-icons:end -->'
/** Resource-safe key for one design in one accent: ids may contain "-", resource names may not. */
export const androidIconKey = (id, accent) => `${id.replace(/[^a-z0-9]+/gi, '_').toLowerCase()}_${hex(accent).slice(1).toLowerCase()}`
export const androidAliasName = (id, accent) => `.AppIcon_${androidIconKey(id, accent)}`
/** The alias that carries the shipped main icon (hand-written in the manifest). */
export const ANDROID_DEFAULT_ALIAS = '.MainLauncher'

/** The accents a variant is pre-rendered in: a fixed-colour design only has its own. */
const accentsOf = (variant) => (variant.fixedColours ? listAccents().slice(0, 1) : listAccents())

/**
 * Android cannot repaint a launcher icon, so every variant x accent gets its own resources and one disabled
 * <activity-alias>. Returns the resource files, the picker catalogue (assets/app-icons.json) and the alias block.
 * The main variant in the first accent is the shipped icon itself (the always-present .MainLauncher alias), so it gets none.
 */
export function androidIconOutputs() {
  const out = new Map()
  const main = mainVariantId()
  const mainAccent = listAccents()[0]
  const colours = []
  const aliases = []
  const catalog = []
  for (const variant of listVariants()) {
    const entry = {id: variant.id, name: variant.name, ...(variant.fixedColours ? {fixedColours: true} : {}), accents: []}
    for (const accent of accentsOf(variant)) {
      const isDefault = variant.id === main && accent === mainAccent
      if (isDefault) {
        entry.accents.push({hex: accent, alias: ANDROID_DEFAULT_ALIAS, preview: 'ic_launcher'})
        continue
      }
      const key = androidIconKey(variant.id, accent)
      const ink = palette(variant, accent).ink
      colours.push(`    <color name="ic_launcher_${key}_background">${ink}</color>`)
      for (const [file, data] of launcherLayers(variant, accent, `ic_launcher_${key}`)) out.set(file, data)
      for (const [folder, themed] of [['mipmap-anydpi-v26', false], ['mipmap-anydpi-v33', true]]) {
        for (const name of [`ic_launcher_${key}`, `ic_launcher_${key}_round`]) {
          out.set(
            `${ANDROID_RES}/${folder}/${name}.xml`,
            `<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_${key}_background"/>
    <foreground android:drawable="@drawable/ic_launcher_${key}_foreground"/>
${themed ? `    <monochrome android:drawable="@drawable/ic_launcher_${key}_monochrome"/>\n` : ''}</adaptive-icon>\n`,
          )
        }
      }
      const alias = androidAliasName(variant.id, accent)
      aliases.push(
        `        <activity-alias android:name="${alias}" android:targetActivity=".MainActivity" android:enabled="false" android:exported="true" android:label="Keepfin" android:icon="@mipmap/ic_launcher_${key}" android:roundIcon="@mipmap/ic_launcher_${key}_round">\n            <intent-filter><action android:name="android.intent.action.MAIN" /><category android:name="android.intent.category.LAUNCHER" /></intent-filter>\n        </activity-alias>`,
      )
      entry.accents.push({hex: accent, alias, preview: `ic_launcher_${key}`})
    }
    catalog.push(entry)
  }
  out.set(`${ANDROID_RES}/values/icon_variant_colors.xml`, `<resources>\n${colours.join('\n')}\n</resources>\n`)
  out.set('android/app/src/main/assets/app-icons.json', JSON.stringify({main, accent: mainAccent, defaultAlias: ANDROID_DEFAULT_ALIAS, icons: catalog}, null, 1) + '\n')
  out.set(ANDROID_MANIFEST, {aliasBlock: `${ALIAS_BEGIN}\n${aliases.join('\n')}\n        ${ALIAS_END}`})
  return out
}

/** Replace the generated alias block in the manifest text (between the two marker comments). */
export function manifestWithAliases(manifest, block) {
  const start = manifest.indexOf('<!-- app-icons:begin')
  const end = manifest.indexOf(ALIAS_END)
  if (start < 0 || end < start) throw new Error('AndroidManifest.xml has no app-icons:begin / app-icons:end markers')
  return manifest.slice(0, start) + block + manifest.slice(end + ALIAS_END.length)
}
