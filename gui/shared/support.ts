// Fixed support destinations. The renderer sends only an id; the main process opens
// the matching URL, so no arbitrary address can be requested from the page.

export type SupportLinkId = 'github' | 'sponsors' | 'kofi' | 'paypal'

export const supportLinks: Record<SupportLinkId, string> = {
  github: 'https://github.com/HishamSanjag',
  sponsors: 'https://github.com/sponsors/HishamSanjag',
  kofi: 'https://ko-fi.com/hishamsanjag',
  paypal: 'https://paypal.me/hishamsanjagpaypal',
}

export function supportLink(id: unknown): string | undefined {
  return typeof id === 'string' && Object.hasOwn(supportLinks, id) ? supportLinks[id as SupportLinkId] : undefined
}
