// Picks the "What's new" pages for one version out of the bundled content (see whatsNewContent.ts).
export type WhatsNewPage = {id: string; title: string; text: string; image?: string}

type Manifest = {pages?: {id?: unknown; windows?: {title?: unknown; text?: unknown}}[]}

/** Pages for `version` that have a Windows entry, each with its picture when one exists. Empty when the version has none. */
export function pagesFor(version: string, manifests: Record<string, unknown>, images: Record<string, string>): WhatsNewPage[] {
  const wanted = version.replace(/^v/i, '')
  const key = Object.keys(manifests).find((name) => /whats-new\/([^/]+)\/pages\.json$/.exec(name)?.[1] === wanted)
  const list = key ? (manifests[key] as Manifest | undefined)?.pages : undefined
  if (!Array.isArray(list)) return []
  const pictures = new Map<string, string>()
  for (const [name, url] of Object.entries(images)) {
    const match = /whats-new\/([^/]+)\/([^/]+)-windows\.png$/.exec(name)
    if (match && match[1] === wanted) pictures.set(match[2], url)
  }
  const pages: WhatsNewPage[] = []
  for (const item of list) {
    const {id, windows} = item ?? {}
    if (typeof id !== 'string' || !windows || typeof windows.title !== 'string' || typeof windows.text !== 'string') continue
    pages.push({id, title: windows.title, text: windows.text, image: pictures.get(id)})
  }
  return pages
}
