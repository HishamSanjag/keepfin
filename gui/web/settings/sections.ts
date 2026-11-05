// The Settings categories, and the redirect map from the section ids earlier versions used. Pure: nav.ts and the search index import it.

export type SettingsCategory = 'downloads' | 'library' | 'storage' | 'devices' | 'appearance' | 'notifications' | 'help' | 'support' | 'about'

/** Section ids that existed before the categories were reorganised. They still work as navigation targets. */
export type LegacySection = 'general' | 'performance' | 'accounts' | 'network' | 'updates' | 'guide'

export type SettingsGroup = SettingsCategory | LegacySection

export const categories: {id: SettingsCategory; label: string; icon: string}[] = [
  {id: 'downloads', label: 'Downloads', icon: 'download'},
  {id: 'library', label: 'Library & playback', icon: 'video-library'},
  {id: 'storage', label: 'Storage & copies', icon: 'folder'},
  {id: 'devices', label: 'Devices', icon: 'devices'},
  {id: 'appearance', label: 'Appearance', icon: 'palette'},
  {id: 'notifications', label: 'Notifications', icon: 'bell'},
  {id: 'help', label: 'Help', icon: 'help'},
  {id: 'support', label: 'Support Keepfin', icon: 'heart'},
  {id: 'about', label: 'About Keepfin', icon: 'info'},
]

export type SettingsTarget = {category: SettingsCategory; /** The setting or group to scroll to and highlight. */ anchor?: string}

const redirects: Record<LegacySection | SettingsCategory, SettingsTarget> = {
  // unchanged ids
  downloads: {category: 'downloads'},
  library: {category: 'library'},
  storage: {category: 'storage'},
  devices: {category: 'devices'},
  appearance: {category: 'appearance'},
  notifications: {category: 'notifications'},
  help: {category: 'help'},
  support: {category: 'support'},
  about: {category: 'about'},
  // old ids
  general: {category: 'appearance'},
  performance: {category: 'downloads', anchor: 'speed'},
  accounts: {category: 'downloads', anchor: 'signin'},
  network: {category: 'downloads', anchor: 'proxy'},
  updates: {category: 'about', anchor: 'updates'},
  guide: {category: 'help', anchor: 'guide'},
}

/** Where a section id (new or old) lands. An unknown id opens the first category. An explicit [anchor] wins over the redirect's own. */
export function resolveSection(id: string, anchor?: string): SettingsTarget {
  const found = Object.prototype.hasOwnProperty.call(redirects, id) ? redirects[id as SettingsGroup] : redirects.downloads
  return {category: found.category, anchor: anchor ?? found.anchor}
}

export const categoryLabel = (id: SettingsCategory) => categories.find((item) => item.id === id)?.label ?? id
