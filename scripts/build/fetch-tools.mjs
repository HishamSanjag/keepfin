// Downloads the engine binaries that ship inside the desktop installer (resources/tools):
// yt-dlp.exe (SHA-256 checked against the release SHA2-256SUMS) and gallery-dl.exe
// (checked against the release SHA256SUMS). Writes build/tools/tools.json, which the
// app reads at first start to seed <userData>/tools. The app updates them afterwards.
import {createHash} from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const out = path.join(root, 'build', 'tools')
const agent = {'User-Agent': 'Keepfin-build'}
const exists = (file) =>
  fs.stat(file).then(
    (s) => s.isFile(),
    () => false,
  )
const sha256 = async (file) =>
  createHash('sha256')
    .update(await fs.readFile(file))
    .digest('hex')

async function get(url, as = 'text') {
  const response = await fetch(url, {
    headers: agent,
    signal: AbortSignal.timeout(300_000),
  })
  if (!response.ok) throw new Error(`${url} -> ${response.status}`)
  if (as === 'json') return response.json()
  if (as === 'buffer') return Buffer.from(await response.arrayBuffer())
  return response.text()
}

function checksums(text) {
  const sums = new Map()
  for (const line of text.split(/\r?\n/)) {
    const match = line.trim().match(/^([a-f0-9]{64})\s+\*?(.+)$/i)
    if (match) sums.set(match[2].trim(), match[1].toLowerCase())
  }
  return sums
}

async function latestTag(repo) {
  try {
    const data = await get(
      `https://api.github.com/repos/${repo}/releases/latest`,
      'json',
    )
    if (/^[\w.-]{1,60}$/.test(data.tag_name)) return data.tag_name
  } catch {}
  const response = await fetch(`https://github.com/${repo}/releases/latest`, {
    redirect: 'manual',
    headers: agent,
  })
  const tag = response.headers
    .get('location')
    ?.match(/\/releases\/tag\/([\w.-]{1,60})$/)?.[1]
  if (!tag) throw new Error(`No release found for ${repo}`)
  return tag
}

const sources = {
  'yt-dlp': async () => {
    const tag = await latestTag('yt-dlp/yt-dlp')
    const base = `https://github.com/yt-dlp/yt-dlp/releases/download/${tag}`
    const sum = checksums(await get(`${base}/SHA2-256SUMS`)).get('yt-dlp.exe')
    return {
      file: 'yt-dlp.exe',
      version: tag,
      url: `${base}/yt-dlp.exe`,
      sha256: sum,
    }
  },
  'gallery-dl': async () => {
    const release = await get(
      'https://codeberg.org/api/v1/repos/mikf/gallery-dl/releases/latest',
      'json',
    )
    const asset = release.assets?.find((a) => a.name === 'gallery-dl.exe')
    const sums = release.assets?.find((a) => a.name === 'SHA256SUMS')
    if (!asset || !sums) throw new Error('gallery-dl release is incomplete')
    const sum = checksums(await get(sums.browser_download_url)).get(
      'gallery-dl.exe',
    )
    return {
      file: 'gallery-dl.exe',
      version: String(release.tag_name).replace(/^v/, ''),
      url: asset.browser_download_url,
      sha256: sum,
    }
  },
}

await fs.mkdir(out, {recursive: true})
let manifest = {}
try {
  manifest = JSON.parse(await fs.readFile(path.join(out, 'tools.json'), 'utf8'))
} catch {}
for (const [name, resolve] of Object.entries(sources)) {
  const target = (file) => path.join(out, file)
  try {
    const source = await resolve()
    if (!source.sha256)
      throw new Error(`no checksum published for ${source.file}`)
    const cached = manifest[name]
    if (
      cached?.sha256 === source.sha256 &&
      (await exists(target(source.file))) &&
      (await sha256(target(source.file))) === source.sha256
    ) {
      console.log(`${name} ${source.version}: up to date`)
      continue
    }
    console.log(`${name} ${source.version}: downloading`)
    const data = await get(source.url, 'buffer')
    const actual = createHash('sha256').update(data).digest('hex')
    if (actual !== source.sha256)
      throw new Error(`checksum mismatch for ${source.file}`)
    await fs.writeFile(target(source.file), data)
    manifest[name] = {
      file: source.file,
      version: source.version,
      sha256: actual,
    }
  } catch (error) {
    // Offline builds reuse a previously verified copy; otherwise the app downloads on first run.
    const kept = manifest[name] && (await exists(target(manifest[name].file)))
    if (!kept) {
      delete manifest[name]
      console.warn(
        `${name}: not bundled (${error.message}). The app will download it on first run.`,
      )
    } else
      console.warn(
        `${name}: using cached ${manifest[name].version} (${error.message})`,
      )
  }
}
await fs.writeFile(
  path.join(out, 'tools.json'),
  JSON.stringify(manifest, null, 2),
)
