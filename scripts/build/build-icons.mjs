// Generates every shipped icon for the main app icon (assets/icons/variants/index.json, field "main").
//   npm run icons:build
//   node scripts/build/build-icons.mjs --variant k-fin --out %TEMP%\kf-icons\switch   (dry run: any variant, writes only under --out)
//   node scripts/build/build-icons.mjs --accent '#C2410C'                              (any accent; default is the variant's own)
// The SVGs in assets/icons/variants are the source of truth; colours are substituted here, never edited per colour.
import fs from 'node:fs/promises'
import path from 'node:path'
import {androidIconOutputs, appIconOutputs, listAccents, manifestWithAliases, listVariants, mainVariantId, outputsFor} from './build-icon-variants.mjs'

const args = process.argv.slice(2)
const option = (name) => {
  const at = args.indexOf(`--${name}`)
  return at >= 0 ? args[at + 1] : undefined
}
const id = option('variant') ?? mainVariantId()
const accent = option('accent')
const base = path.resolve(option('out') ?? '.')

/** Write a generated file; a null value means the file must not exist. */
const write = async (file, data) => {
  const target = path.join(base, file)
  if (data === null) return fs.rm(target, {force: true})
  await fs.mkdir(path.dirname(target), {recursive: true})
  await fs.writeFile(target, data)
}

for (const [file, data] of outputsFor(id, accent)) await write(file, data)
// Pre-rendered app icons for the Windows picker: every variant x accent (not part of a single-variant dry run).
if (!option('variant') && !option('accent')) {
  for (const [file, data] of appIconOutputs()) await write(file, data)
  // Android launcher icons for the picker: resources, the catalogue and one disabled alias per design x accent.
  for (const [file, data] of androidIconOutputs()) {
    if (data === null || typeof data === 'string' || Buffer.isBuffer(data)) await write(file, data)
    else {
      const manifest = await fs.readFile(path.resolve(file), 'utf8')
      await write(file, manifestWithAliases(manifest, data.aliasBlock))
    }
  }
  // The picker's manifest: ids and names come from index.json, so the apps never hard-code the list.
  const manifest = JSON.stringify({
    main: mainVariantId(),
    accent: listAccents()[0],
    variants: listVariants().map((v) => ({id: v.id, name: v.name, ...(v.fixedColours ? {fixedColours: true} : {})})),
  }) + '\n'
  for (const file of ['resources/app-icons/index.json', 'gui/web/settings/appIcons.generated.json']) await write(file, manifest)
}
console.log(
  `Generated Keepfin "${id}" icons (SVG/ICO/PNG, web icons and brand pictures, Android launcher icons and brand pictures) in ${base}`,
)
