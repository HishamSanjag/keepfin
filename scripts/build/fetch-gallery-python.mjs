// Packages the gallery engine for Android: gallery-dl is pure Python, and the download engine library already ships a Python.
// Downloads PINNED pure-Python wheels from PyPI (SHA-256 checked against this manifest), drops compiled extension files and
// writes android/app/src/main/assets/gallery/gdl.zip plus gdl.json ({packages, sha256 of the zip}). The zip is deterministic
// (sorted entries, fixed timestamps), so rebuilds are byte-identical. The app puts the zip on PYTHONPATH (zipimport).
// `--licenses <dir>` also writes each wheel's license files there (used to refresh the bundled notices).
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import zlib from 'node:zlib'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const outDir = path.join(root, 'android', 'app', 'src', 'main', 'assets', 'gallery')

const wheels = [
  {name: 'gallery-dl', version: '1.32.19', file: 'gallery_dl-1.32.19-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/5d/c3/87c38fa0d392455a14b3f32b8a3acf0887ced4689a7b5fc6e0d9f761213f/gallery_dl-1.32.19-py3-none-any.whl', sha256: 'f6b1eb5e9a7409a988b754335d99dbb1873e130d133ed5d164d32e5e645d1c50'},
  {name: 'requests', version: '2.34.2', file: 'requests-2.34.2-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/a0/f4/c67b0b3f1b9245e8d266f0f112c500d50e5b4e83cb6f3b71b6528104182a/requests-2.34.2-py3-none-any.whl', sha256: '2a0d60c172f83ac6ab31e4554906c0f3b3588d37b5cb939b1c061f4907e278e0'},
  {name: 'urllib3', version: '2.8.0', file: 'urllib3-2.8.0-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/92/9d/c4e665119135114480843e7ab388fa94d8480650450e6f8e26b70d323a4c/urllib3-2.8.0-py3-none-any.whl', sha256: '0cf3cae568d36aa9576b28dfb35f11328f1cb974ca7647d9475ebb86c75ac6e3'},
  {name: 'idna', version: '3.20', file: 'idna-3.20-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/58/a2/bb081bab032533a855d44de1d56f8e8426114ff1ba5d1f07a438a0a654f8/idna-3.20-py3-none-any.whl', sha256: 'ab7ae7122974553370f0bdb919e1a960b2cd1bc1ef0276416d896db81c14582c'},
  {name: 'certifi', version: '2026.10.23', file: 'certifi-2026.10.23-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/32/d1/a3652fe2c19134d0d15922177821d863cbd7e842d53a21bbe7e8e3a1b5ac/certifi-2026.10.23-py3-none-any.whl', sha256: 'e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1'},
  {name: 'charset-normalizer', version: '3.5.2', file: 'charset_normalizer-3.5.2-py3-none-any.whl', url: 'https://files.pythonhosted.org/packages/fc/ad/d07d7862a62ffa6d79d68074d14823243dd235a77c45262acbf6adeb28bf/charset_normalizer-3.5.2-py3-none-any.whl', sha256: 'b6b751274acb69d77b3323d6b7dbaa3c7fdfc1eb829b7eb61d262f32e1af9685'},
]

const sha256 = (buffer) => createHash('sha256').update(buffer).digest('hex')
const compiled = /\.(pyd|so|dylib|dll)$/i

/** Reads every file of a zip (wheel) through its central directory: [{name, data}]. */
function readZip(buffer) {
  let eocd = buffer.length - 22
  while (eocd >= 0 && buffer.readUInt32LE(eocd) !== 0x06054b50) eocd--
  if (eocd < 0) throw new Error('not a zip file')
  const count = buffer.readUInt16LE(eocd + 10)
  let offset = buffer.readUInt32LE(eocd + 16)
  const files = []
  for (let i = 0; i < count; i++) {
    if (buffer.readUInt32LE(offset) !== 0x02014b50) throw new Error('bad central directory')
    const method = buffer.readUInt16LE(offset + 10)
    const compressedSize = buffer.readUInt32LE(offset + 20)
    const nameLength = buffer.readUInt16LE(offset + 28)
    const extraLength = buffer.readUInt16LE(offset + 30)
    const commentLength = buffer.readUInt16LE(offset + 32)
    const local = buffer.readUInt32LE(offset + 42)
    const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength)
    offset += 46 + nameLength + extraLength + commentLength
    if (name.endsWith('/')) continue
    const start = local + 30 + buffer.readUInt16LE(local + 26) + buffer.readUInt16LE(local + 28)
    const raw = buffer.subarray(start, start + compressedSize)
    files.push({name, data: method === 0 ? raw : zlib.inflateRawSync(raw)})
  }
  return files
}

const crcTable = Array.from({length: 256}, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
function crc32(data) {
  let c = 0xffffffff
  for (const byte of data) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

/** Writes a deterministic zip: entries in the given order, deflate level 9, timestamp 1980-01-01. */
function writeZip(files) {
  const parts = []
  const central = []
  let offset = 0
  for (const {name, data} of files) {
    const nameBytes = Buffer.from(name, 'utf8')
    const packed = zlib.deflateRawSync(data, {level: 9})
    const crc = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(0x0800, 6) // UTF-8 names
    local.writeUInt16LE(8, 8)
    local.writeUInt16LE(0, 10) // time 00:00:00
    local.writeUInt16LE(0x21, 12) // date 1980-01-01
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(packed.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBytes.length, 26)
    parts.push(local, nameBytes, packed)
    const entry = Buffer.alloc(46)
    entry.writeUInt32LE(0x02014b50, 0)
    entry.writeUInt16LE(20, 4)
    entry.writeUInt16LE(20, 6)
    entry.writeUInt16LE(0x0800, 8)
    entry.writeUInt16LE(8, 10)
    entry.writeUInt16LE(0, 12)
    entry.writeUInt16LE(0x21, 14)
    entry.writeUInt32LE(crc, 16)
    entry.writeUInt32LE(packed.length, 20)
    entry.writeUInt32LE(data.length, 24)
    entry.writeUInt16LE(nameBytes.length, 28)
    entry.writeUInt32LE(offset, 42)
    central.push(entry, nameBytes)
    offset += local.length + nameBytes.length + packed.length
  }
  const directory = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(files.length, 8)
  end.writeUInt16LE(files.length, 10)
  end.writeUInt32LE(directory.length, 12)
  end.writeUInt32LE(offset, 16)
  return Buffer.concat([...parts, directory, end])
}

async function download(wheel) {
  const response = await fetch(wheel.url, {headers: {'User-Agent': 'Keepfin-build'}, signal: AbortSignal.timeout(300_000)})
  if (!response.ok) throw new Error(`${wheel.url} -> ${response.status}`)
  const buffer = Buffer.from(await response.arrayBuffer())
  if (sha256(buffer) !== wheel.sha256) throw new Error(`${wheel.file}: SHA-256 mismatch`)
  return buffer
}

const licenseDir = process.argv.includes('--licenses') ? path.resolve(process.argv[process.argv.indexOf('--licenses') + 1]) : null
const entries = new Map()
for (const wheel of wheels) {
  const cache = path.join(root, 'build', 'gallery-wheels', wheel.file)
  let buffer = await fs.readFile(cache).catch(() => null)
  if (!buffer || sha256(buffer) !== wheel.sha256) {
    buffer = await download(wheel)
    await fs.mkdir(path.dirname(cache), {recursive: true})
    await fs.writeFile(cache, buffer)
  }
  for (const file of readZip(buffer)) {
    const info = /\.dist-info\//.test(file.name)
    if (info && licenseDir && /licen[cs]e|copying|notice/i.test(path.posix.basename(file.name))) {
      await fs.mkdir(licenseDir, {recursive: true})
      await fs.writeFile(path.join(licenseDir, `${wheel.name}-${path.posix.basename(file.name)}`), file.data)
    }
    // Metadata is not needed to import; the licenses ship as plain files in the package notices.
    if (info || file.name.endsWith('.pyc') || compiled.test(file.name)) continue
    if (file.name.startsWith('..') || path.posix.isAbsolute(file.name)) throw new Error(`${wheel.file}: unsafe entry ${file.name}`)
    entries.set(file.name, file.data)
  }
}

const sorted = [...entries.keys()].sort().map((name) => ({name, data: entries.get(name)}))
const zip = writeZip(sorted)
await fs.mkdir(outDir, {recursive: true})
await fs.writeFile(path.join(outDir, 'gdl.zip'), zip)
const manifest = {packages: Object.fromEntries(wheels.map((w) => [w.name, w.version])), sha256: sha256(zip)}
await fs.writeFile(path.join(outDir, 'gdl.json'), `${JSON.stringify(manifest, null, 2)}\n`)
console.log(`gallery engine package: ${sorted.length} files, ${zip.length} bytes, sha256 ${manifest.sha256}`)
