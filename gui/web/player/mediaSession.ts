// navigator.mediaSession: OS media keys, the Windows media flyout and lock-screen style controls.
// One owner at a time (the music player or the video player).
export type SessionInfo = {
  title: string
  artist?: string
  album?: string
  art?: string
  playing: boolean
  position: number
  duration: number
  rate: number
  handlers: Partial<Record<MediaSessionAction, MediaSessionActionHandler>>
}
const actions: MediaSessionAction[] = ['play', 'pause', 'previoustrack', 'nexttrack', 'seekto', 'seekbackward', 'seekforward', 'stop']
let owner: string | undefined
let metaKey = ''

// The Windows media flyout gets no picture for artwork on the app's own keepfin-media:// scheme (the system's image fetch
// only reads data: and web URLs), so it showed a black square. The cover is read here and handed over as a data: URL.
const ART_SIZE = 512
const artCache = new Map<string, string>()

async function artworkData(src: string): Promise<string | undefined> {
  if (src.startsWith('data:')) return src
  const cached = artCache.get(src)
  if (cached) return cached
  const response = await fetch(src)
  if (!response.ok) return undefined
  const bitmap = await createImageBitmap(await response.blob())
  try {
    // Square, centre-cropped, like the covers elsewhere in the app; JPEG keeps the data URL small.
    const side = Math.min(bitmap.width, bitmap.height)
    const canvas = document.createElement('canvas')
    canvas.width = canvas.height = Math.min(ART_SIZE, side)
    canvas.getContext('2d')!.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, canvas.width, canvas.height)
    const data = canvas.toDataURL('image/jpeg', 0.88)
    artCache.set(src, data)
    if (artCache.size > 40) artCache.delete(artCache.keys().next().value!)
    return data
  } finally {
    bitmap.close()
  }
}

function setMetadata(info: SessionInfo, artwork?: string) {
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: info.title,
      artist: info.artist || '',
      album: info.album || '',
      artwork: artwork ? [{src: artwork, sizes: `${ART_SIZE}x${ART_SIZE}`, type: artwork.startsWith('data:image/jpeg') ? 'image/jpeg' : undefined} as MediaImage] : [],
    })
  } catch {
    /* Artwork the platform cannot resolve is simply left out. */
  }
}

export function bindSession(who: string, info: SessionInfo) {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return
  const session = navigator.mediaSession
  const key = [who, info.title, info.artist, info.album, info.art].join('\u0001')
  if (key !== metaKey || owner !== who) {
    metaKey = key
    const art = info.art
    const ready = art ? artCache.get(art) ?? (art.startsWith('data:') ? art : undefined) : undefined
    setMetadata(info, ready)
    // Swap the picture in once it is read, unless the song changed meanwhile.
    if (art && !ready)
      void artworkData(art)
        // A web cover that cannot be read here (no CORS) still works as a plain URL; the app's own scheme does not.
        .catch(() => (/^https?:/i.test(art) ? art : undefined))
        .then((data) => {
          if (data && metaKey === key) setMetadata(info, data)
        })
  }
  if (owner !== who) {
    for (const action of actions) {
      try {
        session.setActionHandler(action, info.handlers[action] || null)
      } catch {
        /* Unsupported action on this platform. */
      }
    }
    owner = who
  }
  session.playbackState = info.playing ? 'playing' : 'paused'
  try {
    if (Number.isFinite(info.duration) && info.duration > 0)
      session.setPositionState({duration: info.duration, playbackRate: info.rate || 1, position: Math.max(0, Math.min(info.position, info.duration))})
  } catch {
    /* Position state is optional. */
  }
}

export function clearSession(who: string) {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator) || owner !== who) return
  const session = navigator.mediaSession
  session.metadata = null
  session.playbackState = 'none'
  for (const action of actions) {
    try {
      session.setActionHandler(action, null)
    } catch {
      /* ignore */
    }
  }
  owner = undefined
  metaKey = ''
}
