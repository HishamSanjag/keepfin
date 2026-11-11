// The only place that globs content/whats-new; the lookup itself is in whatsNewPages.ts.
import {pagesFor} from './whatsNewPages'

const manifests = import.meta.glob('../../content/whats-new/*/pages.json', {eager: true, import: 'default'})
const images = import.meta.glob('../../content/whats-new/*/*-windows.png', {eager: true, query: '?url', import: 'default'}) as Record<string, string>

export const whatsNewPages = (version: string) => pagesFor(version, manifests, images)
