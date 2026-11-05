import {native, request} from './bridge'

// Session cache: each artwork key is fetched once. Components read the resolved value
// synchronously on mount so remounts and re-renders never reload or flash.
const resolved = new Map<string, string | null>()
const inflight = new Map<string, Promise<string | null>>()
const dataUrl = /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/]+=*$/

export type ThumbSource = {jobId?: string; url?: string; ready?: boolean}

function safeHttp(value?: string) {
  if (!value) return
  try {
    const url = new URL(value)
    if (['https:', 'http:'].includes(url.protocol) && !url.username && !url.password) return url.href
  } catch {}
}
export function thumbKey({jobId, url, ready}: ThumbSource) {
  if (jobId) return `job:${jobId}:${ready ? 1 : 0}`
  const safe = safeHttp(url)
  return safe ? `url:${safe}` : ''
}
export function peekThumb(key: string) {
  return resolved.get(key)
}
export function loadThumb(source: ThumbSource): Promise<string | null> {
  const key = thumbKey(source)
  if (!key) return Promise.resolve(null)
  if (resolved.has(key)) return Promise.resolve(resolved.get(key)!)
  let promise = inflight.get(key)
  if (promise) return promise
  promise = (async () => {
    // The browser preview has no artwork proxy; it renders the neutral glyph.
    if (!native) return null
    try {
      const image = await request<unknown>({
        op: 'thumbnail',
        id: source.jobId,
        url: source.jobId ? undefined : safeHttp(source.url),
      })
      return typeof image === 'string' && image.length < 2_800_000 && dataUrl.test(image) ? image : null
    } catch {
      return null
    }
  })().then((image) => {
    resolved.set(key, image)
    inflight.delete(key)
    return image
  })
  inflight.set(key, promise)
  return promise
}
