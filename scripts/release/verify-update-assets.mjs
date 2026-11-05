import fs from 'node:fs'
import path from 'node:path'
import {createHash} from 'node:crypto'

const [directory, requestedVersion] = process.argv.slice(2)
const version =
  requestedVersion ||
  JSON.parse(fs.readFileSync('package.json', 'utf8')).version
if (!directory || !/^\d+\.\d+\.\d+(-beta\.\d+)?$/.test(version || ''))
  throw new Error(
    'Usage: node scripts/release/verify-update-assets.mjs <Windows artifact directory> <version>',
  )
const installer = `Keepfin-Setup-${version}.exe`
const metadata = fs.readFileSync(path.join(directory, 'latest.yml'), 'utf8')
// Restrict to the simple schema emitted by our pinned builder; fail if it changes.
const field = (name) =>
  metadata.match(
    new RegExp(`^${name}: ['"]?([^'"\\r\\n]+)['"]?\\s*$`, 'm'),
  )?.[1]
if (field('version') !== version || field('path') !== installer)
  throw new Error(
    'Update metadata version or installer filename does not match the release',
  )
if (!metadata.includes(`url: ${installer}`))
  throw new Error('Update files entry does not match the release installer')
const hash = createHash('sha512')
for await (const chunk of fs.createReadStream(path.join(directory, installer)))
  hash.update(chunk)
if (hash.digest('base64') !== field('sha512'))
  throw new Error(
    'Update metadata checksum does not match the actual installer',
  )
for (const filename of [
  installer + '.blockmap',
  `Keepfin-Portable-${version}.exe`,
]) {
  if (!fs.statSync(path.join(directory, filename)).size)
    throw new Error(`Missing release asset: ${filename}`)
}
console.log(
  `Verified Windows update metadata, installer checksum, blockmap and portable assets for ${version}`,
)
