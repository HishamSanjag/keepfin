// The one place the renderer turns library ids into URLs for <img>, <audio> and <video>.
// Desktop: keepfin-media:// ids resolved by the main process. The UI fixtures (never the real app) may
// provide window.__KEEPFIN_MEDIA_BASE so the browser can load tiny generated files over http instead.
import {mediaUrl} from '../shared/library'

declare global {
  interface Window {
    __KEEPFIN_MEDIA_BASE?: string
  }
}

function base() {
  return typeof window !== 'undefined' ? window.__KEEPFIN_MEDIA_BASE : undefined
}

export function itemUrl(id: string) {
  const root = base()
  return root ? `${root}/item/${encodeURIComponent(id)}` : mediaUrl('item', id)
}
export function artUrl(id: string) {
  const root = base()
  return root ? `${root}/art/${encodeURIComponent(id)}` : mediaUrl('art', id)
}
export function subtitleUrl(id: string, index: number) {
  const root = base()
  return root ? `${root}/sub/${encodeURIComponent(id)}/${index}` : mediaUrl('sub', id, index)
}
/** A song's music video (the file beside it), by the song's id. */
export function musicVideoUrl(id: string) {
  const root = base()
  return root ? `${root}/video/${encodeURIComponent(id)}` : mediaUrl('video', id)
}
export function jobUrl(id: string) {
  const root = base()
  return root ? `${root}/job/${encodeURIComponent(id)}` : mediaUrl('job', id)
}
/** Absolute URL for the OS media session artwork (it cannot resolve relative or custom-scheme ids on its own). */
export function artworkSrc(id: string) {
  const url = artUrl(id)
  try {
    return new URL(url, location.href).href
  } catch {
    return url
  }
}
