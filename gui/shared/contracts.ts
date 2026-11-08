// Studio 2 contract between the Electron main process (engine, queue) and the renderer.
// The renderer never sends command-line arguments: it sends structured intent
// (FormatRequest + DownloadOptions) and the engine builds validated arguments.
import {checkUrl} from './core/providers.js'
import type {ActivityList} from './activity.js'
import type {NotificationPreferences} from './notifications.js'
import type {CopiesArgs, CopiesState} from './copies.js'
import type {CombineArgs, CombineState} from './combine.js'
import type {DevicesArgs, DevicesState} from './devices.js'
import type {WhereState} from './where.js'
import type {MoveProgress, SetupProgress, StorageArgs, StorageState} from './storage.js'
import type {CollectionFollow, CollectionKind, ExistingMatch, LibrarySettings, LibrarySnapshot, Space} from './library.js'
export type * from './library.js'

// ---------------------------------------------------------------------------
// Media inspection
// ---------------------------------------------------------------------------

export type VideoCodec = 'h264' | 'hevc' | 'vp9' | 'av1' | 'other'
export type AudioCodec = 'aac' | 'opus' | 'mp3' | 'vorbis' | 'flac' | 'other'

/** One selectable video quality. Deduplicated per height + fps + HDR. */
export type VideoOption = {
  /** Stable key within one MediaInfo, e.g. "2160p60-hdr". */
  id: string
  height: number
  width?: number
  fps?: number
  hdr: boolean
  codec: VideoCodec
  /** Native extension of the selected video stream (mp4, webm…). */
  ext: string
  /** Exact yt-dlp format id of the video stream (may already contain audio). */
  formatId: string
  /** True when this stream already carries audio (progressive/muxed). */
  hasAudio: boolean
  /** Estimated total bytes including the best matching audio stream. */
  size?: number
  /** True when size came from filesize_approx or a bitrate×duration estimate. */
  sizeApprox: boolean
  /** Total bitrate in kbit/s when known. */
  tbr?: number
}

/** One selectable source audio stream. */
export type AudioOption = {
  id: string
  formatId: string
  codec: AudioCodec
  ext: string
  /** Average audio bitrate in kbit/s. */
  abr?: number
  size?: number
  sizeApprox: boolean
  language?: string
  /** e.g. "original", "dubbed", "descriptive", "DRC". */
  note?: string
}

export type SubtitleTrack = {lang: string; name?: string; auto: boolean}

export type MusicMetadata = {
  artist?: string
  album?: string
  track?: string
  releaseYear?: number
  /** Tracks on the source album (album collections), used to tell editions apart. */
  trackCount?: number
}

export type MusicMatch = {
  /** 'youtube' = another upload of the same song on YouTube (the original is region-restricted). */
  source: 'spotify' | 'apple-music' | 'deezer' | 'youtube'
  /** Original service URL the user pasted. */
  sourceUrl: string
  /** YouTube Music URL that will be downloaded; absent until matched. */
  matchedUrl?: string
  matchedTitle?: string
  /** 0–1 heuristic confidence. */
  confidence?: number
  /**
   * How the recording was found: 'album' = the track at its place on the same album on YouTube Music, 'search' = the
   * songs search, 'chosen' = the recording the user picked for this source earlier. Absent on older matches.
   */
  method?: 'album' | 'search' | 'chosen'
  /** method 'album': the YouTube Music album and the track's place on it. */
  album?: string
  position?: number
  /** Length of the matched recording in seconds, when known. */
  durationSec?: number
  /** Up to three other uploads of the same recording, best first (for a later "Not right? Pick another"). */
  alternates?: MusicAlternate[]
}

export type MusicAlternate = {
  url: string
  title: string
  durationSec?: number
  confidence: number
}

export type MediaInfo = {
  /** Validated URL the user inspected; the key for enqueue. */
  url: string
  webpageUrl?: string
  title: string
  uploader?: string
  duration?: number
  thumbnail?: string
  /** yt-dlp extractor key or "gallery-dl:<category>". */
  extractor?: string
  /** Human provider name, e.g. "YouTube Music", "TikTok". */
  provider: string
  /** Which engine will perform the download. 'music' = matched via the music resolver. */
  engine: 'yt-dlp' | 'gallery-dl' | 'music'
  /** Present for Spotify/Apple Music/Deezer links resolved to a YouTube Music recording. */
  match?: MusicMatch
  isLive?: boolean
  music?: MusicMetadata
  /** Sorted best first. Empty for audio-only sources and galleries. */
  video: VideoOption[]
  /** Sorted best first. */
  audio: AudioOption[]
  subtitles: SubtitleTrack[]
  chapters: number
  /** Present when the engine is gallery-dl (images / mixed posts). */
  gallery?: {count: number; previews: string[]; items: {position: number; url: string; photo: boolean}[]; truncated: boolean}
  /** Present when the link is a single item inside a playlist (watch?v=…&list=…). */
  playlist?: {url: string; title?: string}
  /** Genre, tags and categories from the source, normalised (see normalizeTags). Kept on the file when the library imports tags. */
  tags?: string[]
}

/** Result of `inspect`: either one media item or a collection to review. */
export type InspectResult =
  | {type: 'media'; media: MediaInfo}
  | {type: 'collection'; url: string; title?: string; uploader?: string}

// ---------------------------------------------------------------------------
// Download intent
// ---------------------------------------------------------------------------

export type Container = 'mp4' | 'mkv' | 'webm'
export type AudioTarget = 'mp3' | 'm4a' | 'opus' | 'flac' | 'wav' | 'original'
export type AudioQuality = 'best' | '320' | '256' | '192' | '128'
export type CodecPreference = 'auto' | 'h264' | 'vp9' | 'av1' | 'hevc'

export type FormatRequest =
  | {
      type: 'video'
      /** Upper bound; undefined means best available. */
      maxHeight?: number
      /** Exact video format id from inspection (single-item downloads). */
      formatId?: string
      /** Exact audio format id from inspection; default best matching. */
      audioFormatId?: string
      /** Prefer this frame rate when choosing within maxHeight (e.g. 60). */
      fps?: number
      codec: CodecPreference
      container: Container
    }
  | {
      type: 'audio'
      target: AudioTarget
      quality: AudioQuality
      formatId?: string
    }
  /** [items]: the 1-based positions to download (all when absent). */
  | {type: 'gallery'; items?: number[]}

export type SponsorBlockMode = 'off' | 'mark' | 'remove'

export type DownloadOptions = {
  subtitles?: {
    enabled: boolean
    /** yt-dlp language selectors, validated: /^[a-zA-Z0-9.*,\-_]{1,100}$/ */
    languages: string
    auto: boolean
    /** Embed into the container (mp4/mkv) instead of only writing sidecars. */
    embed: boolean
  }
  embedThumbnail?: boolean
  embedMetadata?: boolean
  embedChapters?: boolean
  sponsorBlock?: SponsorBlockMode
  /** Download only a time range, seconds. */
  section?: {start?: number; end?: number}
  writeThumbnail?: boolean
  writeDescription?: boolean
  writeInfoJson?: boolean
  /** Split into files per chapter (audio albums / long mixes). */
  splitChapters?: boolean
}

/** Quick presets used by default settings and collection (playlist) downloads. */
export type DownloadPreset =
  | 'best'
  | '2160'
  | '1440'
  | '1080'
  | '720'
  | '480'
  | 'audio-mp3'
  | 'audio-m4a'
  | 'audio-opus'
  | 'audio-flac'

export const downloadPresets: {id: DownloadPreset; label: string}[] = [
  {id: 'best', label: 'Best quality'},
  {id: '2160', label: '4K · 2160p'},
  {id: '1440', label: '2K · 1440p'},
  {id: '1080', label: 'Full HD · 1080p'},
  {id: '720', label: 'HD · 720p'},
  {id: '480', label: 'SD · 480p'},
  {id: 'audio-mp3', label: 'Audio · MP3'},
  {id: 'audio-m4a', label: 'Audio · M4A'},
  {id: 'audio-opus', label: 'Audio · Opus'},
  {id: 'audio-flac', label: 'Audio · FLAC'},
]

// ---------------------------------------------------------------------------
// Collections (playlists, channels, albums, mixes)
// ---------------------------------------------------------------------------

export type PlaylistEntry = {
  url: string
  title?: string
  /** Music resolver entries: source artist/album used for matching and tagging. */
  artist?: string
  album?: string
  thumbnail?: string
  uploader?: string
  duration?: number
  position: number
  /** Music resolver entries of an album: the tracks on the album and its release year, used to match the same edition. */
  albumTrackCount?: number
  releaseYear?: number
  /** Why it cannot be downloaded (private, deleted, gone from YouTube); such an entry is shown but never selected. */
  unavailable?: string
}

export type PlaylistDiscovery = {
  id: string
  title?: string
  uploader?: string
  entries: PlaylistEntry[]
  state: 'discovering' | 'completed' | 'cancelled' | 'failed'
  /** Failure reason; only set when state is 'failed'. */
  error?: string
  /** Non-fatal note on a usable result, e.g. "Spotify lists only the first 100 tracks". */
  notice?: string
  /** Highest position requested so far (pages of 200). */
  limit: number
  /** More entries exist beyond `limit`; the UI can call discoveryMore. */
  truncated: boolean
}

// ---------------------------------------------------------------------------
// Jobs / queue
// ---------------------------------------------------------------------------

export type JobStatus =
  | 'queued'
  | 'preparing'
  | 'downloading'
  | 'processing'
  | 'completed'
  | 'failed'
  | 'cancelled'
  | 'paused'

/** Jobs enqueued together from one collection share a group and render as one set. */
export type JobGroup = {
  id: string
  title: string
  /** Source collection link. */
  url: string
  uploader?: string
  thumbnail?: string
  kind?: 'playlist' | 'album' | 'channel' | 'collection'
}

export const maxAutoRetries = 3

/**
 * Automatic retry state. While `at` is set the job waits (status stays 'queued') and the
 * UI counts down locally; `updating` means the engine is being refreshed before the retry.
 */
export type JobRetry = {
  /** Which automatic retry is pending, 1-based. */
  n: number
  max: number
  /** Epoch ms when the retry starts. */
  at?: number
  updating?: boolean
}

export type Job = {
  id: string
  url: string
  title: string
  thumbnail?: string
  uploader?: string
  duration?: number
  provider?: string
  /** Display label of the chosen format, e.g. "1080p60 · MP4 · H.264". */
  label: string
  kind: 'video' | 'audio' | 'gallery'
  format: FormatRequest
  options: DownloadOptions
  status: JobStatus
  /** 0–100. */
  percent: number
  /** Bytes per second. */
  speed?: number
  /** Seconds remaining. */
  eta?: number
  downloadedBytes?: number
  totalBytes?: number
  /** Estimated size at enqueue time (from inspection). */
  expectedSize?: number
  /** Row text when the download used a sign-in a site asked for: "Signed in" or "Signed in with Firefox". */
  signedIn?: string
  /** Final size on disk once completed. */
  fileSize?: number
  /** Human phase while processing, e.g. "Merging", "Converting to MP3", "Embedding thumbnail". */
  phase?: string
  error?: string
  /** The download finished but could not be saved into the library: what went wrong. The file is safe where it was downloaded; "Save again" tries once more. */
  saveError?: string
  /** Full failure text with tool names removed, for "Details" and bug reports. */
  errorDetail?: string
  file?: string
  /** Additional produced files (subtitles, gallery items, chapter splits). */
  files?: string[]
  outputTemplate?: string
  createdAt: number
  startedAt?: number
  completedAt?: number
  attempt?: number
  /** Present while an automatic retry is pending. */
  retry?: JobRetry
  /** Present when the job belongs to a collection kept together. */
  group?: JobGroup
  /** Music resolver jobs: what was matched and how confidently. */
  match?: MusicMatch
  /** Space the job saves into (library on). */
  space?: string
  /** Completed without downloading because the recording is already in the space; "Download anyway" clears it. */
  existing?: ExistingMatch
  /** Set by "Download anyway": skip the existing-recording check. */
  force?: boolean
  /** A music video download: the finished file is kept beside a song as its video (`Song.video.mp4`), not added as an item of its own. The song is a library item, or a download (`jobId`) that is not in the library yet. */
  attachVideo?: {itemId?: string; jobId?: string}
  /** A song from a collection reviewed with "Also keep music videos": when it finishes, Keepfin looks once for its official video and queues it linked to this job. */
  findVideo?: boolean
  /** "Add to playlist" chosen in the review: when the job finishes, its library item joins this playlist (a collection of kind 'own'). */
  playlist?: string
  /** The collection this job feeds, and its place in the source (0-based), so finished items land in source order. */
  collectionId?: string
  collectionIndex?: number
  /**
   * Set (by the queue, never stored) on a job another paired device asked this one to download ("Download on…"): which device, and, when the job
   * waits for the person here to allow it (it is large, or the connection is metered), why and how large.
   */
  remote?: {device: string; consent?: {reason: 'size' | 'metered'; bytes?: number}}
}

/** Lightweight high-frequency update pushed while a job transfers. */
export type JobProgress = Pick<
  Job,
  | 'id'
  | 'status'
  | 'percent'
  | 'speed'
  | 'eta'
  | 'downloadedBytes'
  | 'totalBytes'
  | 'phase'
>

// ---------------------------------------------------------------------------
// Settings & engine status
// ---------------------------------------------------------------------------

export type FilenameStyle =
  | 'title'
  | 'title-uploader'
  | 'uploader-title'
  | 'date-title'

export type CookieBrowser =
  | 'firefox'
  | 'chrome'
  | 'edge'
  | 'brave'
  | 'opera'
  | 'vivaldi'
  | 'chromium'

export type Settings = {
  outDir: string
  organization: 'platform' | 'flat'
  /** Parallel downloads, 1–6. */
  concurrency: number
  /** Concurrent fragments per download for HLS/DASH, 1–16. */
  fragments: number
  /** Bytes per second; 0 = unlimited. */
  rateLimit: number
  filenameStyle: FilenameStyle
  defaultPreset: DownloadPreset
  /** How music.youtube.com links open: Audio tab (default) or Video. */
  youtubeMusic: 'audio' | 'video'
  /** Defaults applied to new downloads. */
  options: DownloadOptions
  cookies: {mode: 'none' | 'file' | 'browser'; browser?: CookieBrowser}
  /** Downloads go without any sign-in first; when a site then asks for one, a sign-in Keepfin can use is tried once. Off: they stay signed out. A manual cookie source above is always used and keeps this rule from acting. On by default. */
  autoSignIn: boolean
  /** Optional proxy URL (http, https, socks5). Empty = direct. */
  proxy: string
  engine: {channel: 'stable' | 'nightly'; autoUpdate: boolean}
  /** Which app releases to offer: tested releases only, or betas too. Never downgrades. */
  updateChannel: 'stable' | 'beta'
  /** Which page opens at launch: Downloads, Library, or where the user left off. */
  startPage: 'downloads' | 'library' | 'last'
  /** Looks up lyrics for finished songs on LRCLIB and saves them in the song file (or a .lrc next to it). On by default. */
  lyrics: boolean
  /** Songs downloaded as audio also get their official music video: the Capture and collection check boxes start checked. Never starts a download. Off by default. */
  musicVideos: boolean
  /** Windows app icon variant id (from the icon manifest). Empty = the main icon; an id that is not shipped also shows the main icon. */
  appIcon: string
  /** Parallel range-request transfer for direct https streams. */
  turbo: {enabled: boolean; connections: number}
  /** Spaces, gallery and player. Downloads space mirrors outDir/organization/filenameStyle. */
  library: LibrarySettings
}

export type ToolStatus = {
  name: 'yt-dlp' | 'gallery-dl' | 'ffmpeg' | 'js-runtime'
  state: 'ready' | 'missing' | 'installing' | 'updating' | 'error'
  version?: string
  /** Latest version known from the last update check. */
  latest?: string
  checkedAt?: number
  message?: string
}

export type EngineStatus = {tools: ToolStatus[]; busy: boolean}

export type Snapshot = {
  jobs: Job[]
  settings: Settings
  engine: EngineStatus
  platform: 'desktop' | 'preview'
  hasCookieFile: boolean
}

// ---------------------------------------------------------------------------
// IPC
// ---------------------------------------------------------------------------

export type Operation =
  // queue & inspection
  | 'state'
  | 'inspect'
  | 'cancelInspection'
  | 'enqueue'
  /** Capture: the official music video of the inspected song at `url` (answers a MusicVideoFound). */
  | 'findMusicVideo'
  | 'cancel'
  | 'pause'
  | 'resume'
  | 'retry'
  /** A finished download that could not be saved into the library: tries the saving again. */
  | 'saveAgain'
  | 'remove'
  | 'clear'
  | 'moveUp'
  // collection groups
  | 'pauseGroup'
  | 'resumeGroup'
  | 'cancelGroup'
  | 'retryGroup'
  | 'removeGroup'
  | 'removeFailed'
  | 'openGroupLink'
  // music matching
  | 'setMusicChoice'
  | 'musicAlternates'
  // collections
  | 'startDiscovery'
  | 'discoveryState'
  | 'discoveryMore'
  | 'cancelDiscovery'
  | 'enqueueDiscovered'
  // files
  | 'open'
  | 'reveal'
  | 'deleteFile'
  | 'thumbnail'
  // library
  | 'libraryState'
  | 'libraryScan'
  | 'libraryImport'
  | 'libraryMove'
  | 'libraryCancel'
  /** The whole library again, when the window lost track of its pushes: small ones come back whole, big ones as libraryBatch pushes. */
  | 'libraryResync'
  | 'libraryRename'
  | 'libraryEditInfo'
  | 'libraryDeleteFile'
  | 'libraryOpen'
  | 'libraryReveal'
  | 'libraryDrag'
  | 'libraryPlayback'
  | 'libraryFavorite'
  | 'libraryLyrics'
  /** Find lyrics for the song `id` that has none: the answer is `{outcome: 'found' | 'none' | 'busy', message?}`. */
  | 'libraryFindLyrics'
  /** Capture: the song you already have behind the inspected video link `url` (a SongOfLink), or null. */
  | 'songOfLink'
  /** A song's official music video: find it (answers a MusicVideoFound), download it beside the song, or send the video to the Recycle Bin. */
  | 'libraryFindMusicVideo'
  | 'libraryGetMusicVideo'
  | 'libraryRemoveMusicVideo'
  | 'librarySearchLyrics'
  | 'libraryPrepare'
  | 'libraryMatch'
  | 'downloadAnyway'
  | 'spaceSave'
  | 'spaceDelete'
  | 'spaceReorder'
  | 'libraryTag'
  | 'libraryAddFrom'
  /** Takes back one library action by the `undoId` it was sent with (the answer is an UndoResult). */
  | 'libraryUndo'
  /** Delete for good: the Undo notice of `undoId` went without Undo, so what it could bring back is erased now. */
  | 'libraryEraseTrashed'
  /** The Trash page: list what waits in the Keepfin trash (a TrashList), restore `ids`, erase `ids` for good, or erase all (a TrashResult). */
  | 'libraryTrashList'
  | 'libraryTrashRestore'
  | 'libraryTrashErase'
  | 'libraryTrashEmpty'
  // collections (the playlist names stay as aliases for one release)
  | 'collectionSave'
  | 'collectionDelete'
  | 'collectionExport'
  | 'collectionAdd'
  | 'collectionRemove'
  | 'collectionReorder'
  | 'collectionFollow'
  | 'collectionCheck'
  | 'collectionReview'
  | 'collectionOpened'
  | 'playlistSave'
  | 'playlistDelete'
  | 'playlistExport'
  // settings
  | 'updateSettings'
  | 'chooseFolder'
  | 'importCookies'
  | 'clearCookies'
  // engine tools
  | 'engineState'
  | 'engineUpdate'
  // notifications
  | 'notificationState'
  | 'notificationConfigure'
  | 'notificationTest'
  | 'notificationSettings'
  // app updates
  | 'openReleaseLink'
  | 'updateNotes'
  | 'updateState'
  | 'updateCheck'
  | 'updateDownload'
  | 'updateCancel'
  | 'updateInstall'
  | 'updateReleases'
  | 'openSupportLink'
  // the window (dev-docs/plans/DESKTOP-WIDTHS.md)
  | 'windowScale'
  // activity centre
  | 'activityList'
  | 'activityRead'
  | 'activityRemove'
  // the Keepfin folder (gui/shared/storage.ts)
  | 'storageState'
  | 'storagePickFolder'
  | 'storageInspectFolder'
  | 'storageCreate'
  | 'storageOpenExisting'
  | 'storageOverview'
  | 'storageOpenFolder'
  | 'storageCheckFiles'
  | 'storageSetMusicShortcut'
  | 'storageMoveCheck'
  | 'storageMoveRequest'
  | 'storageMoveStart'
  | 'storageMoveCancel'
  | 'setupInventory'
  | 'setupPlan'
  | 'setupStart'
  | 'setupAbandon'
  | 'setupReport'
  | 'setupSaveReport'
  // copies on folders and drives (gui/shared/copies.ts)
  | 'copiesState'
  | 'copiesPickFolder'
  | 'copiesCheckFolder'
  | 'copiesPreview'
  | 'copiesCancelPreview'
  | 'copiesAdd'
  | 'copiesAdopt'
  | 'copiesSync'
  | 'copiesPause'
  | 'copiesEdit'
  | 'copiesRemove'
  | 'copiesResolve'
  | 'copiesVersions'
  | 'copiesRestore'
  | 'copiesCleanup'
  // cloud accounts (copies in Google Drive, OneDrive, Dropbox, WebDAV)
  | 'copiesCloudSignIn'
  | 'copiesCloudCancel'
  | 'copiesCloudWebdav'
  | 'copiesCloudFind'
  | 'copiesCloudAttach'
  | 'copiesCloudDisconnect'
  | 'copiesCloudRevoke'
  // devices on the network (gui/shared/devices.ts)
  | 'devicesState'
  | 'devicesEnable'
  | 'devicesDisable'
  | 'devicesPairingOpen'
  | 'devicesPairingClose'
  | 'devicesQr'
  | 'devicesConnect'
  | 'devicesDecide'
  | 'devicesDismiss'
  | 'devicesRename'
  | 'devicesRemove'
  | 'devicesSyncNow'
  | 'devicesAccess'
  | 'devicesJoin'
  | 'devicesJoinCancel'
  | 'devicesOpenLibrary'
  | 'devicesShare'
  | 'devicesJob'
  | 'devicesJobDismiss'
  | 'devicesPlayFrom'
  | 'devicesFirewallAllow'
  | 'devicesWatch'
  | 'devicesWatchStop'
  | 'remoteJobAllow'
  | 'remoteJobDecline'
  // combining two libraries (gui/shared/combine.ts)
  | 'combineState'
  | 'combinePreview'
  | 'combineStart'
  | 'combinePause'
  | 'combineResume'
  | 'combineCancel'
  | 'combineSeparatePreview'
  | 'combineSeparate'
  | 'combineKeep'
  // where your files are (gui/shared/where.ts): the places and the files only one place holds; the places one file is on (`id` is the item)
  | 'whereState'
  | 'whereItem'
  // Start over (Settings > About Keepfin): erase what this PC keeps, optionally with the Keepfin folder, then start fresh
  | 'startOver'
  /** The outcome of a Start over from the previous run, once (null when there is none). */
  | 'startOverResult'
  // Keepfin's own sign-ins (Settings > Downloads > Website sign-in): the state, the sign-in window for a site (`site` is a site key), signing out of a site
  | 'signInState'
  | 'signInOpen'
  | 'signInRemove'

/** The websites Keepfin can sign in to and which of them it is signed in to. No cookie data, ever. */
export type SignInState = {
  /** False where the operating system cannot encrypt a sign-in for storing (then signing in is not offered). */
  canKeep: boolean
  sites: {key: string; name: string; signedIn: boolean; /** One plain line when the site sign-in cannot happen in Keepfin's own window. */ hint?: string}[]
}

/** What the sign-in window left behind when it was closed. */
export type SignInOutcome = {site: string; signedIn: boolean; message?: string; state: SignInState}

export type StartOverOutcome = {
  deleteFolder: boolean
  /** The Keepfin folder was deleted (only when asked for). */
  folderDeleted: boolean
  /** What this PC kept (settings, history, caches) was erased. */
  dataDeleted: boolean
  /** Things worth saying that are not failures (the folder was already gone). */
  notes?: string[]
  /** Honest problems, in plain words. Empty when everything asked for worked. */
  problems: string[]
}

export type Request = {
  op: Operation
  /** startOver: also delete the Keepfin folder. Main decides which folder; the renderer never sends a path. */
  deleteFolder?: boolean
  /** signInOpen, signInRemove: a site key from the sign-in table (e.g. `instagram`). */
  site?: string
  /** windowScale: the UI scale in percent (80–140); main keeps the window at least 560 px of layout wide. */
  scale?: number
  url?: string
  id?: string
  /** For enqueue / enqueueDiscovered. */
  format?: FormatRequest
  options?: DownloadOptions
  /** For enqueueDiscovered: several entry URLs at once. */
  urls?: string[]
  /** For enqueueDiscovered: keep the entries together as one group (default true). */
  grouped?: boolean
  preset?: DownloadPreset
  /** A patch: main merges `library` field by field (see mergeLibrary). */
  settings?: Partial<Omit<Settings, 'library'>> & {library?: Partial<Settings['library']>}
  notifications?: NotificationPreferences
  inspectionId?: string
  /** enqueueDiscovered: "Keep in sync" for a playlist or channel. Absent leaves following as it is. */
  keepInSync?: {intervalHours: CollectionFollow['intervalHours']}
  /** enqueueDiscovered: "Also keep music videos where there is one" (a music collection downloaded as audio). */
  findVideo?: boolean
  /** enqueue / enqueueDiscovered: "Add to playlist" from the review, a playlist's id. */
  joinPlaylist?: string
  /** enqueue / enqueueDiscovered / libraryMove / libraryImport target space. */
  space?: string
  /** setMusicChoice: use this recording for the Spotify / Apple Music / Deezer link in `url` from now on. */
  musicChoice?: {matchedUrl: string; title?: string; durationSec?: number}
  /**
   * An action the renderer wants to be able to take back is sent with a fresh id. Main records how to reverse it from the state at that
   * moment; `libraryUndo` with the same id reverses exactly that action on the state as it is then.
   */
  undoId?: string
  /** libraryDeleteFile: erase instead of keeping (no Keepfin trash once Undo has run out, no Recycle Bin). */
  forGood?: boolean
  /** Library ops: item ids. */
  ids?: string[]
  /** libraryImport: copy (default) or move picked files. */
  importMode?: 'copy' | 'move'
  /** libraryImport: absolute paths from a native dialog or drop (main re-validates). */
  paths?: string[]
  /** libraryImport without paths: which native picker to open (Windows cannot pick files and folders together). */
  pick?: 'files' | 'folder'
  /** spaceSave. */
  spaceDef?: Space
  /** collectionSave: absent id creates a collection of kind 'own'; downloaded collections can be renamed. */
  collection?: {id?: string; name: string; itemIds?: string[]; space?: string; kind?: CollectionKind}
  /** playlistSave (alias of collectionSave). */
  playlist?: {id?: string; name: string; itemIds: string[]}
  /** collectionFollow: the options to change. */
  follow?: Partial<Pick<CollectionFollow, 'mode' | 'intervalHours' | 'wifiOnly' | 'notify' | 'confirmAbove'>>
  /** libraryTag: user tags to add and remove on `ids`. */
  tags?: {add?: string[]; remove?: string[]}
  /** libraryAddFrom: collections whose items are added, and whether the files move (default) or are copied. */
  collectionIds?: string[]
  addMode?: 'move' | 'copy'
  /** libraryPlayback: resume position (s) and whether playback finished. */
  position?: number
  finished?: boolean
  /** libraryFavorite. */
  favorite?: boolean
  /** libraryRename / librarySearchLyrics. */
  text?: string
  /** libraryGetMusicVideo, and enqueue (an audio download of a song): the video id the find step offered. */
  videoId?: string
  /** libraryOpen, libraryReveal: the song's music video file instead of the song. */
  video?: boolean
  /** libraryEditInfo: new tags (cover as a local path or https URL). */
  /** activityRead: the entries to mark read, or 'all'. activityRemove uses `id`. */
  read?: string[] | 'all'
  /** storage* and setup* operations. */
  storage?: StorageArgs
  /** copies* operations. */
  copies?: CopiesArgs
  /** devices* operations. */
  devices?: DevicesArgs
  /** combine* operations. */
  combine?: CombineArgs
  info?: {title?: string; artist?: string; album?: string; albumArtist?: string; track?: number; disc?: number; year?: number; genre?: string; cover?: string}
}

export type LibraryMeta = {scanning: boolean; scannedAt?: number; problems: LibrarySnapshot['problems']; operation?: LibrarySnapshot['operation']}

export type LibraryDeltaPush = {
  type: 'libraryDelta'
  seq: number
  base: number
  items?: LibrarySnapshot['items']
  removedItems?: string[]
  collections?: LibrarySnapshot['collections']
  removedCollections?: string[]
  meta?: LibraryMeta
}

export type LibraryBatchPush = {
  type: 'libraryBatch'
  seq: number
  index: number
  total: number
  items: LibrarySnapshot['items']
  /** On the first batch. */
  collections?: LibrarySnapshot['collections']
  meta?: LibraryMeta
}

/** Push channels from main → renderer (via preload). */
export type PushEvent =
  | {type: 'snapshot'; snapshot: Snapshot}
  | {type: 'progress'; progress: JobProgress[]}
  | {type: 'discovery'; discovery: PlaylistDiscovery}
  | {type: 'reveal'; tab: 'Downloads' | 'Library'; group?: string}
  | {type: 'library'; library: LibrarySnapshot}
  /** What changed in the library since push `base` (D-T2). A renderer whose current seq is not `base` drops it and asks for libraryResync. */
  | LibraryDeltaPush
  /** A whole library in bounded batches (resync, or a change of unknown extent on a big library). The last batch has index === total - 1. */
  | LibraryBatchPush
  /** An automatic follow check found new items (queued, or held for review when `pending`). */
  | {type: 'follow'; found: {id: string; name: string; added: number; pending?: number}}
  /** The Activity list changed (an entry was recorded, updated, read or removed). */
  | {type: 'activity'; activity: ActivityList}
  /** The storage phase changed (first start done, setup finished). */
  | {type: 'storage'; state: StorageState}
  /** Setup window progress, at most 4 per second. */
  | {type: 'setup'; progress: SetupProgress}
  | {type: 'storageMove'; progress: MoveProgress}
  /** Copies changed: a sync started, moved on or finished, a drive appeared, a decision is waiting. At most 4 per second. */
  | {type: 'copies'; state: CopiesState}
  /** Devices on the network changed: a device appeared, the numbers are ready to compare, a device came or went. */
  | {type: 'devices'; state: DevicesState}
  /** Progress and state of combining two libraries (the combine* operations answer the same shape). */
  | {type: 'combine'; state: CombineState}
  /** The places that hold the library, or the files only one holds, changed (a library change, a copy or a device moved on). At most 2 per second. */
  | {type: 'where'; state: WhereState}
  /** Windows app icon applied; `shortcutsFailed` when a Keepfin shortcut could not be updated. */
  | {type: 'appIcon'; shortcutsFailed: boolean}

// ---------------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------------

export const activeStatuses: JobStatus[] = [
  'queued',
  'preparing',
  'downloading',
  'processing',
]

export function isActive(status: JobStatus) {
  return activeStatuses.includes(status)
}

/** The normalised http(s) link, or an error with the message shown to the user. The core parses the link. */
export function validateUrl(value: unknown): string {
  if (typeof value !== 'string') throw new Error('Paste a valid video link.')
  const check = checkUrl(value)
  if (check.href !== undefined) return check.href
  if (check.problem === 'tooLong') throw new Error('Paste a valid video link.')
  if (check.problem === 'notALink') throw new Error('Paste a complete https:// link.')
  throw new Error('Only HTTP and HTTPS links are supported.')
}

export const subtitleLanguagePattern = /^[a-zA-Z0-9.*,\-_]{1,100}$/

export function formatBytes(bytes?: number): string {
  if (!bytes || !Number.isFinite(bytes) || bytes <= 0) return ''
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export function formatDuration(seconds?: number): string {
  if (!seconds || !Number.isFinite(seconds) || seconds <= 0) return ''
  const s = Math.round(seconds)
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`
}
