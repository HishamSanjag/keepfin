// A static index of every setting for the Settings search: title, a few keywords, category, and the anchor the card carries
// (`id="setting-<anchor>"`). Choosing a result opens the category, scrolls to the anchor and highlights it. Pure.
import {categoryLabel, type SettingsCategory} from './sections'

export type SettingEntry = {
  anchor: string
  title: string
  keywords: string
  category: SettingsCategory
  /** The heading the card sits under, shown after the category in a result. */
  group?: string
}

const e = (category: SettingsCategory, anchor: string, title: string, keywords: string, group?: string): SettingEntry => ({category, anchor, title, keywords, group})

export const settingsIndex: SettingEntry[] = [
  // Downloads
  e('downloads', 'quality', 'Default quality', 'resolution 1080p 720p best preset size', 'Defaults'),
  e('downloads', 'youtube-music', 'YouTube Music links', 'audio video music.youtube spotify apple music', 'Defaults'),
  e('downloads', 'options', 'Default download options', 'subtitles thumbnail cover metadata chapters trim format', 'Defaults'),
  e('downloads', 'save-to', 'Save to', 'folder directory location output where downloads go', 'Location'),
  e('downloads', 'organize', 'Organize files', 'platform subfolder one folder sort', 'Location'),
  e('downloads', 'file-names', 'File names', 'title creator uploader date naming rename', 'Location'),
  e('downloads', 'lyrics', 'Add lyrics to songs', 'lrclib synced timed songs', 'Songs'),
  e('downloads', 'music-videos', 'Keep music videos automatically', 'official video clip songs audio', 'Songs'),
  e('downloads', 'signin', 'Website sign-in', 'cookies login account age restricted private members browser cookies.txt firefox chrome edge', 'Website sign-in'),
  e('downloads', 'speed', 'Speed and connections', 'performance fast slow bandwidth network', 'Speed and connections'),
  e('downloads', 'parallel', 'Parallel downloads', 'queue concurrency simultaneous at the same time', 'Speed and connections'),
  e('downloads', 'fragments', 'Concurrent fragments', 'hls dash streaming segments', 'Speed and connections'),
  e('downloads', 'turbo', 'Turbo transfer', 'multi-connection ranges parallel connections fast', 'Speed and connections'),
  e('downloads', 'connections', 'Connections per file', 'turbo threads ranges', 'Speed and connections'),
  e('downloads', 'speed-limit', 'Speed limit', 'bandwidth throttle cap limit mb/s', 'Speed and connections'),
  e('downloads', 'proxy', 'Proxy', 'socks socks5 http https vpn network url', 'Speed and connections'),
  // Library & playback
  e('library', 'mode', 'Keepfin mode', 'downloader full library player home search', 'Library'),
  e('library', 'open-with', 'Open media with', 'player default app play external', 'Library'),
  e('library', 'home-releases', 'Show new releases on Home', 'follow following artists new releases card home dismiss hide', 'Library'),
  e('library', 'routes', 'Where downloads go', 'spaces music routing everything else route', 'Where downloads go'),
  e('library', 'spaces', 'Spaces', 'folders smart new space edit remove music videos', 'Spaces'),
  e('library', 'tags', 'Import tags from sources', 'genres categories tag', 'Tags'),
  e('library', 'normalize', 'Volume normalisation', 'loudness quiet loud replaygain volume level', 'Playback'),
  e('library', 'crossfade', 'Crossfade', 'gapless fade songs overlap', 'Playback'),
  // Storage & copies
  e('storage', 'folder', 'Keepfin folder', 'location path drive free space disk open folder', 'Keepfin folder'),
  e('storage', 'files', 'Check files', 'missing health verify found moved deleted', 'Keepfin folder'),
  e('storage', 'copies', 'Copies', 'backup mirror sync two-way add a copy drive external', 'Copies'),
  e('storage', 'cloud', 'Cloud accounts', 'google drive onedrive dropbox webdav sign in account', 'Cloud accounts'),
  e('storage', 'review', 'Needs review', 'conflicts differences decisions problems', 'Needs review'),
  e('storage', 'windows-music', 'Show in Windows Music', 'shortcut music folder windows', 'Windows'),
  e('storage', 'start-over-storage', 'Start over', 'reset delete everything erase wipe clean fresh factory', 'Start over'),
  // Devices
  e('devices', 'status', 'This device, its name and Windows Firewall', 'reachable visible network firewall allow keepfin blocked blocking public turn on off switch rename name device name', 'This device'),
  e('devices', 'paired', 'Your devices', 'paired permissions remove share library phone laptop connected route', 'Your devices'),
  e('devices', 'nearby', 'Available devices', 'nearby pair connect phone computer found scan looking same wifi network tailscale discover add by address code qr numbers link', 'Available devices'),
  e('devices', 'away', 'Away from home', 'tailscale vpn remote outside anywhere magicdns', 'Away from home'),
  e('devices', 'offers', 'Libraries on other devices', 'get this library join shared', 'Libraries'),
  e('devices', 'advanced', 'Advanced', 'fingerprint port libraries signed', 'Advanced'),
  // Appearance
  e('appearance', 'theme', 'Theme', 'dark light system colour mode', 'Look'),
  e('appearance', 'player-style', 'Player style', 'full player stage cinema afterglow layout lyrics visualizer glow video picture', 'Look'),
  e('appearance', 'size', 'Interface size', 'scale zoom text size 80% 140% bigger smaller', 'Look'),
  e('appearance', 'start-page', 'Open Keepfin on', 'startup start page home downloads where i left off last page', 'When Keepfin opens'),
  // Notifications
  e('notifications', 'notify-enabled', 'System notifications', 'alerts banners windows toast', 'Alerts'),
  e('notifications', 'notify-completed', 'Completed downloads', 'finished done alert', 'Alerts'),
  e('notifications', 'notify-failed', 'Download failures', 'errors failed alert', 'Alerts'),
  e('notifications', 'notify-sound', 'Notification sounds', 'sound chime quiet', 'Alerts'),
  e('notifications', 'notify-progress', 'Taskbar progress', 'taskbar progress bar minimized', 'Alerts'),
  // Help & support
  e('help', 'guide', 'Features & how-to', 'help guide tutorial how to learn', 'Help'),
  e('help', 'shortcuts', 'Keyboard shortcuts', 'keys hotkeys ctrl search focus play pause space escape', 'Help'),
  e('support', 'support', 'Support Keepfin', 'donate sponsor coffee paypal ko-fi developer thanks help', 'Support'),
  // About Keepfin
  e('about', 'about', 'About Keepfin', 'version licence edition portable installed', 'About'),
  e('about', 'updates', 'Updates', 'version check for updates restart channel beta stable new release', 'Updates'),
  e('about', 'whats-new', 'What’s new', 'release notes changelog new features', 'Updates'),
  e('about', 'engine', 'Download engine', 'auto update nightly early access site fixes tools', 'Download engine'),
  e('about', 'start-over', 'Start over', 'reset delete everything erase wipe clean fresh factory', 'Start over'),
]

const norm = (text: string) => text.toLowerCase().replace(/[’']/g, '').replace(/\s+/g, ' ').trim()

/** The entries matching [query], best first: a title that starts with the first word, then any title match, then keywords and category. */
export function searchSettings(query: string, limit = 8): SettingEntry[] {
  const words = norm(query).split(' ').filter(Boolean)
  if (!words.length) return []
  const scored: {entry: SettingEntry; score: number; order: number}[] = []
  settingsIndex.forEach((entry, order) => {
    const title = norm(entry.title)
    const rest = norm(`${entry.keywords} ${entry.group ?? ''} ${categoryLabel(entry.category)}`)
    let score = 0
    for (const word of words) {
      if (title.split(' ').some((part) => part.startsWith(word))) score += title.startsWith(word) ? 5 : 4
      else if (title.includes(word)) score += 3
      else if (rest.includes(word)) score += 1
      else return
    }
    scored.push({entry, score, order})
  })
  return scored.sort((a, b) => b.score - a.score || a.order - b.order).slice(0, limit).map((item) => item.entry)
}

/** "Downloads › Speed and connections" */
export const entryPath = (entry: SettingEntry) => [categoryLabel(entry.category), entry.group].filter((part, index, all) => part && (index === 0 || part !== all[0])).join(' › ')
