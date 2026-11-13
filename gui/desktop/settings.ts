import path from 'node:path'
import {
  downloadPresets,
  type CookieBrowser,
  type DownloadPreset,
  type FilenameStyle,
  type Settings,
} from '../shared/contracts.js'
import {
  insertKept,
  isKeptSpace,
  keptSpaceIds,
  withKeptSpaces,
  builtinSpaceIds,
  builtinTones,
  defaultLibrarySettings,
  folderSpaces,
  spaceColors,
  spaceIcons,
  type FolderLayout,
  type LibraryItemKind,
  type LibrarySettings,
  type SmartRule,
  type SmartRules,
  type Space,
  type SpaceColor,
  type SpaceKind,
  type SpaceNaming,
} from '../shared/library.js'
import {
  cookieBrowsers,
  validateOptions,
  validateProxy,
} from '../shared/policy.js'

export const MAX_CONCURRENCY = 6

export function defaultSettings(
  outDir: string,
  folders: {music: string; videos: string} = {
    music: path.join(path.dirname(outDir), 'Keepfin Music'),
    videos: path.join(path.dirname(outDir), 'Keepfin Videos'),
  },
): Settings {
  return {
    outDir,
    organization: 'platform',
    concurrency: 3,
    fragments: 8,
    rateLimit: 0,
    filenameStyle: 'title',
    defaultPreset: 'best',
    youtubeMusic: 'audio',
    options: {embedMetadata: true, embedThumbnail: true, embedChapters: true},
    cookies: {mode: 'none'},
    autoSignIn: true,
    proxy: '',
    engine: {channel: 'stable', autoUpdate: true},
    updateChannel: 'stable',
    startPage: 'last',
    lyrics: true,
    musicVideos: false,
    newReleasesOnHome: true,
    appIcon: '',
    turbo: {enabled: false, connections: 8},
    library: defaultLibrarySettings({downloads: outDir, ...folders}),
  }
}

const filenameStyles: FilenameStyle[] = [
  'title',
  'title-uploader',
  'uploader-title',
  'date-title',
]

const clampInt = (
  value: unknown,
  min: number,
  max: number,
  fallback: number,
) =>
  typeof value === 'number' && Number.isFinite(value)
    ? Math.min(max, Math.max(min, Math.round(value)))
    : fallback

function pick<T extends string>(
  value: unknown,
  allowed: readonly T[],
  fallback: T,
): T {
  return typeof value === 'string' &&
    (allowed as readonly string[]).includes(value)
    ? (value as T)
    : fallback
}

/**
 * Merges a renderer-supplied partial over the current settings. Numbers are clamped,
 * enums fall back to the current value, and invalid free text (proxy, languages) throws
 * so the user sees why the change was rejected. Folder writability is checked by the
 * manager (it needs the file system).
 */
export function mergeSettings(current: Settings, input: unknown): Settings {
  if (!input || typeof input !== 'object' || Array.isArray(input))
    throw new Error('Invalid settings.')
  const patch = input as Record<string, unknown>
  const next: Settings = structuredClone(current)
  if (patch.appIcon !== undefined)
    next.appIcon =
      typeof patch.appIcon === 'string' && /^[a-z0-9][a-z0-9-]{0,39}$/.test(patch.appIcon) ? patch.appIcon : ''
  if (patch.outDir !== undefined) {
    if (
      typeof patch.outDir !== 'string' ||
      !path.isAbsolute(patch.outDir) ||
      patch.outDir.length > 1000
    )
      throw new Error('Choose a download folder.')
    next.outDir = path.normalize(patch.outDir)
  }
  if (patch.organization !== undefined)
    next.organization = pick(
      patch.organization,
      ['platform', 'flat'] as const,
      current.organization,
    )
  if (patch.concurrency !== undefined)
    next.concurrency = clampInt(
      patch.concurrency,
      1,
      MAX_CONCURRENCY,
      current.concurrency,
    )
  if (patch.fragments !== undefined)
    next.fragments = clampInt(patch.fragments, 1, 16, current.fragments)
  if (patch.rateLimit !== undefined)
    next.rateLimit = clampInt(
      patch.rateLimit,
      0,
      10_000_000_000,
      current.rateLimit,
    )
  if (patch.filenameStyle !== undefined)
    next.filenameStyle = pick(
      patch.filenameStyle,
      filenameStyles,
      current.filenameStyle,
    )
  if (patch.defaultPreset !== undefined)
    next.defaultPreset = pick(
      patch.defaultPreset,
      downloadPresets.map((p) => p.id) as DownloadPreset[],
      current.defaultPreset,
    )
  if (patch.youtubeMusic !== undefined)
    next.youtubeMusic = pick(
      patch.youtubeMusic,
      ['audio', 'video'] as const,
      current.youtubeMusic,
    )
  if (patch.options !== undefined) next.options = validateOptions(patch.options)
  if (patch.cookies !== undefined) {
    const cookies = patch.cookies as Record<string, unknown>
    if (!cookies || typeof cookies !== 'object')
      throw new Error('Invalid cookie settings.')
    const mode = pick(
      cookies.mode,
      ['none', 'file', 'browser'] as const,
      current.cookies.mode,
    )
    const browser =
      cookies.browser === undefined
        ? current.cookies.browser
        : pick<CookieBrowser>(cookies.browser, cookieBrowsers, 'firefox')
    if (mode === 'browser' && !browser)
      throw new Error('Choose a browser to read cookies from.')
    next.cookies =
      mode === 'browser' ? {mode, browser} : browser ? {mode, browser} : {mode}
  }
  if (typeof patch.autoSignIn === 'boolean') next.autoSignIn = patch.autoSignIn
  if (patch.proxy !== undefined) next.proxy = validateProxy(patch.proxy)
  if (patch.engine !== undefined) {
    const engine = patch.engine as Record<string, unknown>
    if (!engine || typeof engine !== 'object')
      throw new Error('Invalid engine settings.')
    next.engine = {
      channel: pick(
        engine.channel,
        ['stable', 'nightly'] as const,
        current.engine.channel,
      ),
      autoUpdate:
        typeof engine.autoUpdate === 'boolean'
          ? engine.autoUpdate
          : current.engine.autoUpdate,
    }
  }
  if (patch.updateChannel !== undefined)
    next.updateChannel = pick(
      patch.updateChannel,
      ['stable', 'beta'] as const,
      current.updateChannel,
    )
  if (typeof patch.lyrics === 'boolean') next.lyrics = patch.lyrics
  if (typeof patch.musicVideos === 'boolean') next.musicVideos = patch.musicVideos
  if (typeof patch.newReleasesOnHome === 'boolean') next.newReleasesOnHome = patch.newReleasesOnHome
  if (patch.startPage !== undefined)
    next.startPage = pick(
      patch.startPage,
      ['downloads', 'library', 'last'] as const,
      current.startPage,
    )
  if (patch.turbo !== undefined) {
    const turbo = patch.turbo as Record<string, unknown>
    if (!turbo || typeof turbo !== 'object')
      throw new Error('Invalid Turbo settings.')
    next.turbo = {
      enabled:
        typeof turbo.enabled === 'boolean'
          ? turbo.enabled
          : current.turbo.enabled,
      connections: clampInt(
        turbo.connections,
        2,
        16,
        current.turbo.connections,
      ),
    }
  }
  if (patch.library !== undefined) next.library = mergeLibrary(current.library, patch.library)
  const libraryPatch = patch.library as Record<string, unknown> | undefined
  if (libraryPatch && typeof libraryPatch === 'object' && libraryPatch.spaces !== undefined) legacyFromDownloads(next)
  else if (patch.outDir !== undefined || patch.organization !== undefined || patch.filenameStyle !== undefined)
    downloadsFromLegacy(next, current, patch)
  return next
}

// ---------------------------------------------------------------------------
// Library settings
// ---------------------------------------------------------------------------

const spaceKinds: SpaceKind[] = ['music', 'video', 'mixed']
const layouts: FolderLayout[] = ['flat', 'collection', 'platform', 'artist-album']
const namings: SpaceNaming[] = [...filenameStyles, 'artist-title']
const spaceId = /^[a-z0-9][a-z0-9_-]{0,39}$/
export const MAX_SPACES = 50

/** Absolute, normalised, not a drive/filesystem root. */
export function validateFolder(value: unknown): string {
  if (typeof value !== 'string' || !path.isAbsolute(value) || value.length > 1000)
    throw new Error('Choose a folder for this space.')
  const normalized = path.normalize(value)
  if (normalized === path.parse(normalized).root) throw new Error('Choose a folder, not a whole drive.')
  return normalized.replace(/[\\/]+$/, '')
}

function choose<T extends string>(value: unknown, allowed: readonly T[], fallback: T, message: string): T {
  if (value === undefined) return fallback
  if (typeof value !== 'string' || !(allowed as readonly string[]).includes(value)) throw new Error(message)
  return value as T
}

const ruleKinds: LibraryItemKind[] = ['video', 'audio', 'image', 'other']
const listFields = ['tag', 'collection', 'space', 'source', 'artist', 'album', 'kind'] as const

/** Validates a smart space's rules: known fields, bounded lists, no blank values. */
export function validateSmartRules(input: unknown): SmartRules {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Invalid rules.')
  const raw = input as Record<string, unknown>
  const match = choose(raw.match, ['any', 'all'] as const, 'any', 'Choose whether any or all rules must match.')
  if (!Array.isArray(raw.rules) || raw.rules.length > 30) throw new Error('A smart space can have up to 30 rules.')
  const rules = raw.rules.map((item): SmartRule => {
    if (!item || typeof item !== 'object') throw new Error('Invalid rules.')
    const rule = item as Record<string, unknown>
    const field = rule.field
    if ((listFields as readonly unknown[]).includes(field)) {
      if (!Array.isArray(rule.values) || rule.values.length > 50) throw new Error('Invalid rules.')
      const values = [
        ...new Set(rule.values.map((value) => (typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '')).filter(Boolean)),
      ]
      if (values.some((value) => value.length > 100)) throw new Error('Rule values can be up to 100 characters.')
      return {field: field as (typeof listFields)[number], values}
    }
    if (field === 'addedWithin') {
      if (typeof rule.days !== 'number' || !Number.isFinite(rule.days)) throw new Error('Choose a number of days.')
      return {field, days: Math.min(3650, Math.max(1, Math.round(rule.days)))}
    }
    if (field === 'favorite' || field === 'unplayed') return {field}
    throw new Error('Unknown rule.')
  })
  const out: SmartRules = {match, rules}
  if (raw.onlyKind !== undefined) out.onlyKind = choose(raw.onlyKind, ruleKinds, 'audio', 'Unknown kind.')
  if (raw.excludeSpaces !== undefined) {
    if (!Array.isArray(raw.excludeSpaces) || raw.excludeSpaces.length > 50) throw new Error('Invalid rules.')
    const ids = [...new Set(raw.excludeSpaces.filter((value): value is string => typeof value === 'string' && spaceId.test(value)))]
    if (ids.length) out.excludeSpaces = ids
  }
  return out
}

export const KEPT_SPACES_MESSAGE = 'Downloads, Music, Videos and Favorites can be renamed but not removed.'

/** Validates one space definition. A missing or unknown id is the caller's to assign. */
export function validateSpace(input: unknown): Space {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Invalid space.')
  const raw = input as Record<string, unknown>
  const id = typeof raw.id === 'string' ? raw.id : ''
  if (!spaceId.test(id)) throw new Error('Invalid space.')
  if (raw.deviceOnly === true) throw new Error('Music on this PC is shown or hidden in Settings.')
  const name = typeof raw.name === 'string' ? raw.name.replace(/\s+/g, ' ').trim() : ''
  if (!name) throw new Error('Give this space a name.')
  if (name.length > 60) throw new Error('Space names can be up to 60 characters.')
  const type = choose(raw.type, ['folder', 'smart'] as const, 'folder', 'Choose a folder or smart space.')
  const builtin = (builtinSpaceIds as readonly string[]).includes(id)
  if (builtin && type === 'smart') throw new Error('Downloads, Music and Videos hold files and cannot be smart spaces.')
  const color = raw.color === undefined ? builtinTones[id]?.color : choose<SpaceColor>(raw.color, spaceColors, 'cobalt', 'Choose one of the space colors.')
  const icon = raw.icon === undefined ? builtinTones[id]?.icon : choose(raw.icon, spaceIcons, 'folder', 'Choose one of the space icons.')
  if (raw.pinned !== undefined && typeof raw.pinned !== 'boolean') throw new Error('Invalid space.')
  const pinned = raw.pinned ?? builtin
  if (type === 'smart') {
    const smart: Space = {
      id,
      name,
      kind: 'mixed',
      folder: '',
      layout: 'flat',
      naming: 'title',
      skipExisting: false,
      type,
      pinned,
      rules: raw.rules === undefined ? {match: 'any', rules: []} : validateSmartRules(raw.rules),
    }
    if (color) smart.color = color
    if (icon) smart.icon = icon
    return smart
  }
  const kind = choose(raw.kind, spaceKinds, 'mixed', 'Choose music, video or mixed.')
  const layout = choose(raw.layout, layouts, 'flat', 'Unknown folder layout.')
  if (layout === 'artist-album' && kind !== 'music') throw new Error('Artist and album folders are only for music spaces.')
  const naming = choose(raw.naming, namings, 'title', 'Unknown file name style.')
  if (naming === 'artist-title' && kind === 'video') throw new Error('Artist - title names are for music and mixed spaces.')
  const space: Space = {
    id,
    name,
    kind,
    folder: validateFolder(raw.folder),
    layout,
    naming,
    skipExisting: typeof raw.skipExisting === 'boolean' ? raw.skipExisting : kind === 'music',
    type,
    pinned,
  }
  if (color) space.color = color
  if (icon) space.icon = icon
  if (raw.ignore !== undefined) {
    if (!Array.isArray(raw.ignore) || raw.ignore.length > 200) throw new Error('Invalid ignored folders.')
    const ignore = [
      ...new Set(
        raw.ignore.map((item) => {
          const folder = typeof item === 'string' ? item.replace(/\\/g, '/').replace(/^\/+|\/+$/g, '').trim() : ''
          if (!folder || folder.length > 300 || folder.split('/').some((part) => part === '..' || part === '.' || !part))
            throw new Error('Ignored folders are names relative to the space folder.')
          return folder
        }),
      ),
    ]
    if (ignore.length) space.ignore = ignore
  }
  if (raw.scanFolder === true) space.scanFolder = true
  return space
}

const sameFolder = (a: string, b: string) => (process.platform === 'win32' ? a.toLowerCase() === b.toLowerCase() : a === b)

/** The whole space list: kept spaces (Downloads, Music, Videos, Favorites) present, ids and folders unique. */
export function validateSpaces(input: unknown): Space[] {
  if (!Array.isArray(input) || !input.length || input.length > MAX_SPACES) throw new Error('Invalid spaces.')
  const spaces = input.map(validateSpace)
  let custom = 0
  for (const [index, space] of spaces.entries()) {
    if (spaces.findIndex((other) => other.id === space.id) !== index) throw new Error('Two spaces cannot share an id.')
    if (space.type === 'folder' && spaces.some((other, at) => at < index && other.type === 'folder' && sameFolder(other.folder, space.folder)))
      throw new Error('Two spaces cannot use the same folder.')
    // the kept spaces (the built-ins and Favorites) have their own colors and take no turn of the palette
    if (!isKeptSpace(space.id)) {
      // Custom spaces take the next palette color once and keep it.
      space.color ??= (['teal', 'olive', 'amber', 'rust', 'berry', 'cobalt', 'violet', 'slate'] as const)[custom % 8]
      custom++
    }
    if (!space.icon) space.icon = space.type === 'smart' ? 'star' : space.kind === 'music' ? 'music' : space.kind === 'video' ? 'video' : 'folder'
  }
  for (const id of keptSpaceIds())
    if (!spaces.some((space) => space.id === id)) throw new Error(KEPT_SPACES_MESSAGE)
  return spaces
}

function routeTarget(value: unknown, fallback: string) {
  if (value === undefined) return fallback
  if (typeof value !== 'string' || !spaceId.test(value)) throw new Error('Choose one of your spaces.')
  return value
}

function mergeLibrary(current: LibrarySettings, input: unknown): LibrarySettings {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Invalid library settings.')
  const patch = input as Record<string, unknown>
  const next = structuredClone(current)
  if (patch.enabled !== undefined) {
    if (typeof patch.enabled !== 'boolean') throw new Error('Invalid library settings.')
    next.enabled = patch.enabled
  }
  if (patch.openWith !== undefined) next.openWith = choose(patch.openWith, ['keepfin', 'system'] as const, current.openWith, 'Invalid library settings.')
  if (patch.normalize !== undefined) {
    if (typeof patch.normalize !== 'boolean') throw new Error('Invalid library settings.')
    next.normalize = patch.normalize
  }
  if (patch.crossfade !== undefined) {
    if (typeof patch.crossfade !== 'number' || !Number.isFinite(patch.crossfade)) throw new Error('Crossfade is a number of seconds.')
    next.crossfade = Math.round(Math.min(12, Math.max(0, patch.crossfade)) * 10) / 10
  }
  if (patch.spaces !== undefined) next.spaces = validateSpaces(patch.spaces)
  if (patch.routes !== undefined) {
    const routes = patch.routes as Record<string, unknown>
    if (!routes || typeof routes !== 'object' || Array.isArray(routes)) throw new Error('Invalid routes.')
    next.routes = {
      music: routeTarget(routes.music, current.routes.music),
      other: routeTarget(routes.other, current.routes.other),
    }
  }
  if (patch.importTags !== undefined) {
    if (typeof patch.importTags !== 'boolean') throw new Error('Invalid library settings.')
    next.importTags = patch.importTags
  }
  if (patch.offered !== undefined) {
    if (!Array.isArray(patch.offered) || patch.offered.length > 50 || patch.offered.some((id) => typeof id !== 'string' || !spaceId.test(id))) throw new Error('Invalid library settings.')
    next.offered = [...new Set(patch.offered as string[])]
  }
  for (const key of ['music', 'other'] as const)
    if (!folderSpaces(next.spaces).some((space) => space.id === next.routes[key])) {
      // A route can never point at a space that no longer exists.
      if (patch.routes !== undefined) throw new Error('Choose one of your spaces.')
      next.routes[key] = key === 'music' ? 'music' : 'downloads'
    }
  return next
}

/** Library → legacy: the Downloads space is authoritative for outDir/organization/filenameStyle. */
export function legacyFromDownloads(settings: Settings) {
  const downloads = settings.library.spaces.find((space) => space.id === 'downloads')
  if (!downloads) return
  settings.outDir = downloads.folder
  settings.organization = downloads.layout === 'platform' ? 'platform' : 'flat'
  if (downloads.naming !== 'artist-title') settings.filenameStyle = downloads.naming
}

/** Legacy → library: keeps the Downloads space in step with the settings that predate spaces. */
function downloadsFromLegacy(next: Settings, current: Settings, patch: Record<string, unknown>) {
  const downloads = next.library.spaces.find((space) => space.id === 'downloads')
  if (!downloads) return
  if (patch.outDir !== undefined) downloads.folder = next.outDir
  if (patch.organization !== undefined && next.organization !== current.organization) {
    if (next.organization === 'platform') downloads.layout = 'platform'
    else if (downloads.layout === 'platform') downloads.layout = 'flat'
  }
  if (patch.filenameStyle !== undefined) downloads.naming = next.filenameStyle
}

/** Stored library settings from before Favorites was kept (or without it): its space list gets it, so the list still validates. */
function withStoredKeptSpaces(library: unknown): unknown {
  if (!library || typeof library !== 'object' || Array.isArray(library)) return library
  const stored = library as Record<string, unknown>
  if (!Array.isArray(stored.spaces)) return library
  const spaces = insertKept(stored.spaces as unknown[], (space) => (space && typeof space === 'object' ? ((space as {id?: unknown}).id as string | undefined) : undefined))
  return spaces.length === stored.spaces.length ? library : {...stored, spaces}
}

/**
 * Loads persisted settings defensively: anything invalid falls back to defaults. Settings saved before
 * spaces existed (0.4.x) get a library whose Downloads space mirrors outDir, organization and file names.
 */
export function loadSettings(defaults: Settings, stored: unknown): Settings {
  let result = defaults
  if (stored && typeof stored === 'object') {
    // Apply field by field so one bad value does not discard the rest.
    for (const [key, value] of Object.entries(stored as Record<string, unknown>)) {
      try {
        result = mergeSettings(result, {[key]: key === 'library' ? withStoredKeptSpaces(value) : value})
      } catch {}
    }
  }
  const hasLibrary = !!stored && typeof stored === 'object' && (stored as Record<string, unknown>).library !== undefined
  result = structuredClone(result)
  // the kept spaces (Favorites): a new install has them from the start, an earlier version's library or one that lost it gets it back
  result.library.spaces = withKeptSpaces(result.library.spaces)
  if (hasLibrary && result.library.spaces.some((space) => space.id === 'downloads')) legacyFromDownloads(result)
  else {
    const downloads = result.library.spaces.find((space) => space.id === 'downloads')
    if (downloads) {
      downloads.folder = result.outDir
      downloads.layout = result.organization === 'platform' ? 'platform' : 'flat'
      downloads.naming = result.filenameStyle
    }
  }
  return result
}
