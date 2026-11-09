// Runs the language-neutral engine fixtures in fixtures/engine/** against the real shared and desktop
// logic. The same files are meant to be run by the Android JVM tests (see fixtures/engine/KOTLIN-RUNNER.md),
// so a drift between the two implementations fails a test on at least one side.
//
// Case shape: {name, input, expected, platforms?, suspectedMismatch?}. `expected` is matched as a subset of the
// normalised result (objects: expected keys only; arrays: same length, element by element; null: null/absent).
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {validateUrl} from './contracts.js'
import {audioCodec, estimateSize, heightLabel, mediaInfoFromYtDlp, subtitleTracks, videoCodec, type RawFormat, type RawInfo} from './formats.js'
import {formatArgs, jobQuote, metadataArgs, PROGRESS_PREFIX, presetFormat, validateFormatRequest, validateOptions} from './policy.js'
import {formatByteText} from './core/args.js'
import {errorDetailText, scrubToolNames} from './errors.js'
import {analyze, nextRetry, suggestsGallery} from './core/errors.js'
import {
  isCollectionUrl, isGalleryHost, musicLink, platformFolder, playlistContext, prefersAudio, providerName, startsAsVideo,
} from './providers.js'
import {isoDate, musicService, parseUrl} from './core/providers.js'
import {safeName} from './output.js'
import {compareReleases, isValidVersion, pickRelease, releaseHighlights, whatsNewDue} from './core/versions.js'
import * as playbackCore from './core/playback.js'
import {containerAfterPick, containerOffer, modesOf, openingPicks, picksForPreset, picksForTarget, requestOf, sizeOf, videoLabel, type ChoicePicks, type ChoiceSource} from './core/choice.js'
import {cleanSongTitle, findLyricsWords, lyricsAreTimed, preferTimedLyrics, lyricsExactUrl, lyricsFindUrl, lyricsInExactAnswer, lyricsInSearchAnswer, lyricsOutcome, lyricsOutcomeText, lyricsRetryAfterMs, lyricsServiceBusy, normalizeLyricsKey} from './core/lyrics.js'
import {compareRecordings, normalizeArtists, normalizeTitle} from './core/dedupe.js'
import {youTubeVideoId} from '../desktop/engine/music.js'
import {
  canonicalSourceUrl, channelArtist, collectSourceTags, collectionFromPlaylist, collectionKindOf, defaultFollow, defaultSpaceFor, entryKey, gateNew, homeSpaces, isInfiniteSource,
  describeSmartRules, matchesSmartRules, matchExisting, newEntries, normalizeTags, outputStem, outputSubfolders, removedFromSource, reorderSpaces, shiftId, sameRecording, sortBySourceOrder,
  sourceIdFromUrl, sourceIdOfCollection, tagFilterName, tagFilterRules, tagsInScope,
  type Collection, type CollectionKind, type LibraryItem, type LibrarySettings, type Recording, type SmartRules, type Space,
} from './library.js'
import {
  agoWords, bothWords, playlistLabel, playlistWords, collectionKindWords, collectionRowWords, everyWords, extrasCount, extrasLabel, followLineWords, intervalLabel, musicVideoItemId, musicVideoRowId, musicVideoRows, musicVideoWords, switchStart, whereAgo, whereHolds, whereItemLine, whereOnePlace, whereSingle, whereState,
} from './core/spaces.js'
import {cleanTitle} from './title.js'
import {ProgressParser} from '../desktop/engine/progress.js'
import {
  albumApplies, albumHint, albumSearchRequest, bestMatch, bestMusicVideo, parseMusicVideos, matchViaAlbum, parseAlbumPageText, parseAlbumSearchText, parseFlatSearchText, parseYtMusicSearchText, pickBest, rankAlbums, rankAlternates, searchPlan, searchQuery, type MatchCandidate as Candidate, type MatchTrack,
} from './core/music.js'
import {isDue} from '../desktop/library/follow.js'
import {planAfter} from './core/after.js'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../fixtures/engine')

type Json = null | boolean | number | string | Json[] | {[key: string]: Json}
type Obj = Record<string, any> // eslint-disable-line @typescript-eslint/no-explicit-any
type Case = {name: string; input: Obj; expected: Json; platforms?: string[]; suspectedMismatch?: string}
type FixtureFile = {description: string; cases: Case[]}

const categories = ['formats', 'args', 'progress', 'errors', 'providers', 'music', 'dedupe', 'versions', 'spaces', 'playback', 'lyrics', 'choice', 'after'] as const
type Category = (typeof categories)[number]

// ---------------------------------------------------------------------------------------------------------------
// Comparison
// ---------------------------------------------------------------------------------------------------------------

/** Normalises a result to plain JSON (drops undefined, so `null` expectations match absent values). */
const plain = (value: unknown): Json => (value === undefined ? null : (JSON.parse(JSON.stringify(value)) as Json))

function assertSubset(actual: unknown, expected: Json, where: string): void {
  if (expected === null) return assert.ok(actual === null || actual === undefined, `${where}: expected null, got ${JSON.stringify(actual)}`)
  if (Array.isArray(expected)) {
    assert.ok(Array.isArray(actual), `${where}: expected an array, got ${JSON.stringify(actual)}`)
    assert.equal(actual.length, expected.length, `${where}: array length (actual ${JSON.stringify(actual)})`)
    expected.forEach((item, index) => assertSubset(actual[index], item, `${where}[${index}]`))
    return
  }
  if (typeof expected === 'object') {
    assert.ok(actual && typeof actual === 'object' && !Array.isArray(actual), `${where}: expected an object, got ${JSON.stringify(actual)}`)
    for (const [key, value] of Object.entries(expected)) {
      const got: unknown = (actual as Obj)[key]
      // Estimated sizes are rounded by TypeScript and truncated by Kotlin: they agree within one byte.
      if (key === 'size' && typeof value === 'number' && typeof got === 'number') assert.ok(Math.abs(got - value) <= 1, `${where}.${key}: ${String(got)} vs ${value}`)
      else assertSubset(got, value, `${where}.${key}`)
    }
    return
  }
  assert.deepEqual(actual, expected, `${where}`)
}

// ---------------------------------------------------------------------------------------------------------------
// formats
// ---------------------------------------------------------------------------------------------------------------

function infoOf(input: Obj): RawInfo {
  if (input.infoFile) return JSON.parse(fs.readFileSync(path.join(root, 'formats', 'samples', input.infoFile), 'utf8')) as RawInfo
  return input.info as RawInfo
}

function formats(input: Obj): Json {
  switch (input.op) {
    case 'media': {
      const media = mediaInfoFromYtDlp(infoOf(input), input.url)
      const pickVideo: string[] | undefined = input.pickVideo
      const pickAudio: string[] | undefined = input.pickAudio
      const video = media.video.map((v) => ({
        id: v.id, height: v.height, fps: v.fps ?? null, hdr: v.hdr, codec: v.codec, formatId: v.formatId, hasAudio: v.hasAudio,
        size: v.size ?? null, sizeApprox: v.sizeApprox,
      }))
      const audio = media.audio.map((a) => ({
        formatId: a.formatId, codec: a.codec, language: a.language ?? null, size: a.size ?? null, sizeApprox: a.sizeApprox,
      }))
      return plain({
        provider: media.provider, title: media.title, uploader: media.uploader ?? null, duration: media.duration ?? null, isLive: !!media.isLive,
        chapters: media.chapters, playlistUrl: media.playlist?.url ?? null, thumbnail: media.thumbnail ?? null,
        music: media.music
          ? {artist: media.music.artist ?? null, album: media.music.album ?? null, track: media.music.track ?? null, releaseYear: media.music.releaseYear ?? null}
          : null,
        videoIds: media.video.map((v) => v.id),
        video: pickVideo ? pickVideo.map((id) => video.find((v) => v.id === id) ?? null) : video,
        audio: pickAudio ? pickAudio.map((id) => audio.find((a) => a.formatId === id) ?? null) : audio,
        hasVideoOptions: media.video.length > 0,
        hasAudioOptions: media.audio.length > 0,
      })
    }
    case 'size': {
      const size = estimateSize(input.format as RawFormat, input.duration)
      return plain({size: size.size ?? null, approx: size.approx})
    }
    case 'codec':
      return input.kind === 'video' ? videoCodec(input.value) : audioCodec(input.value)
    case 'title':
      return cleanTitle(input.title ?? undefined, input.uploader ?? undefined, input.provider, input.kind ?? 'video')
    case 'pageUrl':
      return mediaInfoFromYtDlp(infoOf(input), input.url).webpageUrl ?? null
    case 'heightLabel':
      return heightLabel(input.height)
    case 'subtitles':
      return plain(subtitleTracks(infoOf(input)).map((t) => ({lang: t.lang, auto: t.auto})))
  }
  throw new Error(`unknown formats op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// args: a semantic plan, never the full command line
// ---------------------------------------------------------------------------------------------------------------

const valuedFlags = new Set([
  '-f', '-S', '--merge-output-format', '--remux-video', '--audio-format', '--audio-quality', '--sub-langs', '--convert-thumbnails',
  '--convert-subs', '--sponsorblock-mark', '--sponsorblock-remove', '--download-sections', '--ppa', '-o',
])

function planOf(args: string[]): Json {
  const values = new Map<string, string>()
  const flags = new Set<string>()
  for (let i = 0; i < args.length; i++) {
    if (valuedFlags.has(args[i])) values.set(args[i], args[++i])
    else flags.add(args[i])
  }
  const section = values.get('--download-sections')
  const parsedSection = section ? /^\*([\d.]+)-([\d.]+|inf)$/.exec(section) : null
  const audioFormat = values.get('--audio-format')
  const target = !audioFormat || audioFormat === 'best' ? 'original' : audioFormat
  return plain({
    selector: values.get('-f') ?? '',
    sort: values.get('-S') ?? null,
    merge: values.get('--merge-output-format') ?? null,
    remux: values.get('--remux-video') ?? null,
    audio: flags.has('-x') ? {format: target, quality: ['mp3', 'm4a', 'opus'].includes(target) ? (values.get('--audio-quality') ?? null) : null} : null,
    subtitles: flags.has('--write-subs')
      ? {langs: values.get('--sub-langs') ?? null, auto: flags.has('--write-auto-subs'), embed: flags.has('--embed-subs')}
      : null,
    embedThumbnail: flags.has('--embed-thumbnail'),
    squareCover: values.has('--ppa'),
    writeThumbnail: flags.has('--write-thumbnail'),
    embedMetadata: flags.has('--embed-metadata'),
    embedChapters: flags.has('--embed-chapters'),
    sponsorBlock: values.has('--sponsorblock-remove') ? 'remove' : values.has('--sponsorblock-mark') ? 'mark' : null,
    section: parsedSection ? {start: Number(parsedSection[1]), end: parsedSection[2] === 'inf' ? null : Number(parsedSection[2])} : null,
  })
}

function args(input: Obj): Json {
  const format = () => validateFormatRequest(input.format)
  const extra = {squareCover: input.squareCover === true, chapterTemplate: input.chapterTemplate as string | undefined}
  switch (input.op) {
    case 'plan':
      return planOf(formatArgs(format(), validateOptions(input.options), extra))
    case 'args':
      return formatArgs(format(), validateOptions(input.options), extra)
    case 'validateFormat':
      try {
        validateFormatRequest(input.format)
        return {valid: true}
      } catch {
        return {valid: false}
      }
    case 'validateOptions':
      try {
        validateOptions(input.options)
        return {valid: true}
      } catch {
        return {valid: false}
      }
    case 'preset': {
      const preset = presetFormat(input.preset, input.container)
      return plain(
        preset.type === 'video'
          ? {type: 'video', maxHeight: preset.maxHeight ?? null, codec: preset.codec, container: preset.container}
          : preset.type === 'audio'
            ? {type: 'audio', target: preset.target, quality: preset.quality}
            : {type: 'gallery'},
      )
    }
    case 'presetValid':
      try {
        presetFormat(input.preset)
        return {valid: true}
      } catch {
        return {valid: false}
      }
    case 'metadata':
      return metadataArgs(input.title ?? undefined)
    case 'quote': {
      const quote = jobQuote(format(), {video: input.video, audio: input.audio, duration: input.duration}, input.withSize !== false)
      return plain({label: quote.label, expectedSize: quote.expectedSize ?? null, approx: quote.approx})
    }
    case 'bytes':
      return formatByteText(input.bytes)
  }
  throw new Error(`unknown args op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// progress
// ---------------------------------------------------------------------------------------------------------------

const na = (value: unknown) => (typeof value === 'number' ? String(value) : 'NA')

function progress(input: Obj): Json {
  const parser = new ProgressParser({formatSizes: input.streamSizes, audioTarget: input.audioTarget})
  const checkpoints: Json[] = []
  ;(input.steps as Obj[]).forEach((step, index) => {
    if (step.type === 'line') parser.line(step.text)
    else
      parser.line(
        `${PROGRESS_PREFIX}${step.formatId}|${step.status}|${na(step.downloaded)}|${na(step.total)}|${na(step.estimate)}|${na(step.speed)}|${na(step.eta)}|${na(step.fragmentIndex)}|${na(step.fragmentCount)}`,
      )
    const p = parser.progress()
    checkpoints[index] = plain({
      percent: p.percent, downloadedBytes: p.downloadedBytes ?? null, totalBytes: p.totalBytes ?? null, speed: p.speed ?? null, eta: p.eta ?? null,
      phase: parser.phaseKey ?? null,
      processing: parser.processing,
    })
  })
  return checkpoints
}

/** Progress expectations are `{after: <step>, ...}`; numbers match within 1 (percent, speed, eta) because the platforms round differently. */
function assertProgress(actual: Json, expected: Json, name: string) {
  for (const want of expected as Obj[]) {
    const got = (actual as Obj[])[want.after]
    assert.ok(got, `${name}: no step ${want.after}`)
    const { after, ...rest } = want
    for (const key of ['percent', 'speed', 'eta']) {
      if (rest[key] === undefined) continue
      if (rest[key] === null) assert.equal(got[key], null, `${name} step ${after} ${key}`)
      else assert.ok(typeof got[key] === 'number' && Math.abs(got[key] - rest[key]) <= 1, `${name} step ${after} ${key}: ${got[key]} vs ${rest[key]}`)
      delete rest[key]
    }
    assertSubset(got, rest as Json, `${name} step ${after}`)
  }
}

// ---------------------------------------------------------------------------------------------------------------
// errors: class + retry kind, plus "no tool names"
// ---------------------------------------------------------------------------------------------------------------

const toolNames = /yt-?dlp|yt_dlp|youtube-?dl|ffmpeg|ffprobe|gallery-dl|aria2/i

// The class, retry kind, scrubbing and retry pacing come from the shared core, through the desktop adapter.
function errors(input: Obj): Json {
  const text = String(input.text)
  if (input.op === 'scrub') {
    const scrubbed = scrubToolNames(text)
    const detail = errorDetailText({stderr: text}) ?? ''
    return {noToolNames: !toolNames.test(scrubbed) && !toolNames.test(detail)}
  }
  if (input.op === 'retry') return plain(nextRetry(input.retriesUsed ?? 0, text, input.crashed === true, 'desktop') ?? null)
  if (input.op === 'suggestsGallery') return {suggests: suggestsGallery(text)}
  assert.equal(input.op, 'classify')
  const result = analyze(text, 'desktop')
  return {
    class: result.errorClass,
    retry: result.retry,
    noToolNames: !toolNames.test(result.message) && !toolNames.test(result.detail ?? ''),
  }
}

// ---------------------------------------------------------------------------------------------------------------
// providers
// ---------------------------------------------------------------------------------------------------------------

function providers(input: Obj): Json {
  const url: string = input.url
  switch (input.op) {
    case 'name': return providerName(url, input.extractor)
    case 'folder': return platformFolder(url)
    case 'musicService': return musicService(url) ?? null
    case 'musicLink': return plain(musicLink(url) ?? null)
    case 'collection': return isCollectionUrl(url)
    case 'playlistUrl': return playlistContext(url)?.url ?? null
    case 'startsAsVideo': return startsAsVideo(url, input.youtubeMusic ?? 'audio')
    case 'preferAudio': return prefersAudio(url, input.youtubeMusic ?? 'audio')
    case 'gallery': return isGalleryHost(url)
    case 'videoId': return youTubeVideoId(url) ?? null
    case 'safeName': return safeName(String(input.value))
    case 'validateUrl':
      try {
        return {valid: true, href: validateUrl(input.value)}
      } catch {
        return {valid: false}
      }
    case 'url': {
      const parsed = parseUrl(input.value)
      // The fixtures treat an empty query or fragment as absent (the href keeps a lone "?" or "#").
      return parsed && plain({...parsed, query: parsed.query || null, fragment: parsed.fragment || null})
    }
    case 'isoDate': return isoDate(input.millis)
    case 'channelArtist': return channelArtist(input.value) ?? null
    case 'outputSubfolders': return outputSubfolders(input.layout, input.job)
    case 'outputStem': return outputStem(input.naming, input.job)
  }
  throw new Error(`unknown providers op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// music
// ---------------------------------------------------------------------------------------------------------------

const watch = (id: string) => `https://music.youtube.com/watch?v=${id}`

function trackOf(input: Obj): MatchTrack {
  const t = input.track as Obj
  return {
    title: t.title,
    artists: t.artists ?? [],
    album: t.album,
    durationMs: t.durationSec ? Math.round(t.durationSec * 1000) : undefined,
    trackNumber: t.trackNumber,
    trackCount: t.trackCount,
    year: t.year,
  }
}

function candidatesOf(input: Obj): Candidate[] {
  return (input.candidates as Obj[]).map((c) => ({
    url: watch(c.id), title: c.title, artists: c.artists ?? (c.channel ? [String(c.channel).replace(/ - Topic$/, '')] : []), durationSec: c.durationSec, album: c.album,
    origin: c.origin ?? 'youtube', channel: c.channel,
  }))
}

/** The text of a capture from music/samples (`file`) or inline data (`data`). */
const musicText = (input: Obj): string =>
  input.file ? fs.readFileSync(path.join(root, 'music', 'samples', input.file), 'utf8') : JSON.stringify(input.data)

const idOf = (candidate: Candidate) => candidate.url.slice(-11)

const sample = (file: string): string => fs.readFileSync(path.join(root, 'music', 'samples', file), 'utf8')

// Every music op goes through the shared core (gui/shared/core/music.ts), the code the app runs.
async function music(input: Obj): Promise<Json> {
  switch (input.op) {
    case 'songsApi':
      return plain({candidates: parseYtMusicSearchText(musicText(input)).map(({url, ...rest}) => ({id: url.slice(-11), ...rest}))})
    case 'flat':
      return plain({candidates: parseFlatSearchText(musicText(input), input.origin).map(({url, ...rest}) => ({id: url.slice(-11), url, ...rest}))})
    case 'searches':
      return plain(searchPlan(trackOf(input)))
    case 'pick': {
      // The app's stop rule: each search returns its candidates or throws ("fail"); the count is how many were attempted.
      const {best, searchesRun} = await pickBest(
        trackOf(input),
        (input.searches as (Obj[] | 'fail')[]).map((result) => async () => {
          if (result === 'fail') throw new Error('search failed')
          return candidatesOf({candidates: result})
        }),
      )
      return plain({bestId: best ? idOf(best.candidate) : null, searchesRun})
    }
    case 'best': {
      const match = bestMatch(trackOf(input), candidatesOf(input))
      return plain({bestId: match ? idOf(match.candidate) : null, confidence: match?.confidence ?? null})
    }
    case 'alternates': {
      const ranked = rankAlternates(trackOf(input), candidatesOf(input), {exclude: input.exclude ?? []})
      return plain({ids: ranked.map((r) => idOf(r.candidate))})
    }
    case 'query':
      return searchQuery(trackOf(input))
    case 'albumSearch':
      return plain({albums: parseAlbumSearchText(musicText(input)).map(({browseId, ...rest}) => ({id: browseId, ...rest}))})
    case 'albumPage': {
      const page = parseAlbumPageText(musicText(input))
      if (!page) return null
      return plain({
        title: page.title,
        artists: [...page.artists],
        year: page.year ?? undefined,
        trackCount: page.trackCount,
        tracks: [...page.tracks].map((t) => ({id: t.videoId, title: t.title, artists: [...t.artists], durationSec: t.durationSec ?? undefined, position: t.position})),
      })
    }
    case 'albumQuery': {
      const track = trackOf(input)
      return plain({applies: albumApplies(track), query: albumApplies(track) ? albumSearchRequest(track).query : undefined})
    }
    case 'videosParse':
      return plain({
        candidates: parseMusicVideos(musicText(input)).map((c) => ({
          id: c.videoId, type: c.type, title: c.title, artists: c.artists, ...(c.views !== undefined ? {views: c.views} : {}), ...(c.durationSec !== undefined ? {durationSec: c.durationSec} : {}),
        })),
      })
    case 'videoBest': {
      const best = bestMusicVideo(input.artists as string[], String(input.title), parseMusicVideos(musicText(input)), (input.artistIds as string[] | undefined) ?? [])
      return plain({videoId: best?.videoId ?? null})
    }
    case 'albumHint':
      return plain({album: albumHint(trackOf(input), candidatesOf(input)) ?? null})
    case 'albumRank':
      return plain({ids: rankAlbums(trackOf(input), parseAlbumSearchText(sample(input.search))).map((a) => a.browseId)})
    case 'album': {
      // The app's loop (matchViaAlbum): search, then the pages the core asks for. Pages are captures; "fail" cannot be read.
      const fetched: string[] = []
      const found = await matchViaAlbum(trackOf(input), {
        search: async () => parseAlbumSearchText(sample(input.search)),
        page: async (browseId) => {
          fetched.push(browseId)
          const file = (input.pages as Obj)[browseId]
          if (file === undefined) throw new Error(`no page for ${browseId}`)
          if (file === 'fail') throw new Error('page failed')
          return parseAlbumPageText(sample(file))
        },
      })
      return plain(
        found
          ? {kind: 'matched', browseId: found.browseId, albumTitle: found.albumTitle, position: found.position, videoId: idOf(found.candidate), title: found.candidate.title, fetched}
          : {kind: 'none', fetched},
      )
    }
  }
  throw new Error(`unknown music op ${input.op}`)
}

/** `minConfidence` is a floor on the winner's confidence; the platforms score differently, so exact values are never compared. */
function assertMusic(actual: Json, expected: Json, name: string) {
  if (expected === null || typeof expected !== 'object' || Array.isArray(expected)) return assertSubset(actual, expected, name)
  const { minConfidence, ...rest } = expected as Obj
  if (minConfidence !== undefined) assert.ok(((actual as Obj).confidence ?? 0) >= minConfidence, `${name}: confidence ${(actual as Obj).confidence} < ${minConfidence}`)
  assertSubset(actual, rest as Json, name)
}

// ---------------------------------------------------------------------------------------------------------------
// dedupe
// ---------------------------------------------------------------------------------------------------------------

const recordingOf = (r: Obj): Recording => ({artist: r.artist, title: r.title, album: r.album, duration: r.duration, sourceIds: r.sourceIds, contentKey: r.contentKey, isrc: r.isrc})

function dedupe(input: Obj): Json {
  switch (input.op) {
    case 'sourceId': return sourceIdFromUrl(input.url) ?? null
    case 'normalizeTitle': return normalizeTitle(input.title, input.artist)
    case 'normalizeArtists': return normalizeArtists(input.artist)
    case 'same': return {match: sameRecording(recordingOf(input.a), recordingOf(input.b))}
    case 'compare': return {tier: compareRecordings(recordingOf(input.a), recordingOf(input.b)) ?? null}
    case 'existing': {
      const items = (input.items as Obj[]).map((i) => ({path: `/${i.id}`, ...i}))
      const found = matchExisting(items as never[], {sourceId: input.probe.sourceId, isrc: input.probe.isrc, recording: input.probe.recording ? recordingOf(input.probe.recording) : undefined})
      return {itemId: found ? (found.item as Obj).id : null, by: found?.by ?? null}
    }
  }
  throw new Error(`unknown dedupe op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// versions
// ---------------------------------------------------------------------------------------------------------------

const unv = (value: string) => value.replace(/^v/, '')

function versions(input: Obj): Json {
  switch (input.op) {
    case 'compare': return Math.sign(compareReleases(input.a, input.b))
    case 'pick': {
      const picked = pickRelease(input.versions, input.channel, input.current)
      return picked ? unv(picked) : null
    }
    case 'valid': return isValidVersion(input.value)
    case 'highlights': return releaseHighlights(input.notes, input.platform, input.limit)
    case 'whatsNewDue': return whatsNewDue(input.seen, input.current)
  }
  throw new Error(`unknown versions op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// playback (the desktop players' adapter, gui/shared/core/playback.ts)
// ---------------------------------------------------------------------------------------------------------------

function playback(input: Obj): Json {
  const nul = (value: unknown) => (value === undefined ? null : (value as Json))
  switch (input.op) {
    case 'skips': {
      let run: playbackCore.SkipRun | undefined
      return (input.steps as Obj[]).map((step) => playbackCore.skipLabel((run = playbackCore.accumulateSkip(run, step.delta, step.now)).total))
    }
    case 'announce': return playbackCore.skipAnnouncement(input.total)
    case 'resume': return nul(playbackCore.resumeFrom(input.position ?? undefined, input.duration ?? undefined, input.watched))
    case 'chapterAt': return playbackCore.chapterIndexAt(input.starts, input.time)
    case 'chapterTarget': return nul(playbackCore.chapterTarget(input.starts, input.time, input.dir))
    case 'crossed': return playbackCore.crossedChapter(input.starts, input.from, input.to)
    case 'list': return playbackCore.playbackList(input.context, input.folder ?? undefined)
    case 'neighbour': return nul(playbackCore.neighbour(input.list, input.id, input.dir))
    case 'folderOrder': return playbackCore.folderOrder(input.items)
    case 'clock': return playbackCore.clock(input.seconds)
    case 'timeLeft': return playbackCore.timeLeft(input.seconds)
    case 'speeds': return [...playbackCore.speeds]
  }
  throw new Error(`unknown playback op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// spaces
// ---------------------------------------------------------------------------------------------------------------

const DAY = 86_400_000

function itemOf(raw: Obj, now: number): LibraryItem {
  const { addedDaysAgo, ...rest } = raw
  return {
    title: `Song ${raw.id}`, space: 'music', path: `C:/${raw.id}`, folder: '', kind: 'audio', size: 1, modifiedAt: 1, hasArt: false,
    addedAt: now - (addedDaysAgo ?? 0) * DAY, ...rest,
  } as LibraryItem
}

const spaceOf = (raw: Obj): Space =>
  ({name: raw.id, kind: 'mixed', folder: `C:/${raw.id}`, layout: 'flat', naming: 'title', skipExisting: false, type: 'folder', ...raw}) as Space

const WORDS_NOW = 1_800_000_000_000
const wordsDate = (time: number) => `date ${time}`

/** The collection a words case describes: kind, source link and follow settings (mode, interval, ageMs of the last check). */
function collectionOfWords(input: Obj): Collection {
  return {
    kind: input.kind, ...(input.sourceUrl ? {source: {url: input.sourceUrl}} : {}),
    ...(input.mode ? {follow: {mode: input.mode, intervalHours: input.intervalHours ?? 6, ...(input.ageMs === undefined ? {} : {lastCheckedAt: WORDS_NOW - input.ageMs})}} : {}),
  } as Collection
}

function spaces(input: Obj): Json {
  switch (input.op) {
    case 'smart': {
      const now = input.now as number
      const membership: Record<string, string[]> = input.collectionsOf ?? {}
      const context = {now, collectionsOf: (id: string) => membership[id] ?? []}
      const items = (input.items as Obj[]).map((raw) => itemOf(raw, now))
      return {ids: items.filter((item) => matchesSmartRules(input.rules as SmartRules, item, context)).map((item) => item.id)}
    }
    case 'ruleWords': {
      const names: Record<string, Record<string, string>> = input.names ?? {}
      return describeSmartRules(input.rules as SmartRules, (field, value) => names[field]?.[value])
    }
    case 'tagRules': return plain(tagFilterRules(input.tags, input.match, input.space))
    case 'tagName': return tagFilterName(input.tags)
    case 'collectionWord': return collectionKindWords(input.kind)
    case 'everyWords': return everyWords(input.hours)
    case 'intervalLabel': return intervalLabel(input.hours)
    case 'followLine': return followLineWords(collectionOfWords(input), input.press ?? 'tap') ?? null
    case 'ago': return agoWords(WORDS_NOW - input.ageMs, WORDS_NOW, wordsDate)
    case 'rowSubtitle': return collectionRowWords(collectionOfWords(input), input.noun ?? 'song', input.present, input.extras ?? [], WORDS_NOW, wordsDate)
    case 'switchStart': return switchStart(input.position ?? Number.NaN, input.otherLength ?? undefined)
    case 'musicVideoRows':
      return {
        rows: musicVideoRows((input.items as Obj[]).map((item) => ({id: item.id, kind: item.kind, title: item.title, artist: item.artist, musicVideo: item.hasVideo, videoDuration: item.videoDuration, videoSize: item.videoSize, addedAt: item.addedAt}))).map((row) => ({
          ...row, artist: row.artist ?? null, duration: row.duration ?? null, size: row.size ?? null,
        })),
      }
    case 'musicVideoRowId': return musicVideoRowId(input.itemId)
    case 'musicVideoItemId': return musicVideoItemId(input.rowId) ?? null
    case 'musicVideoWords': return Object.values(musicVideoWords)
    case 'previewWords': return Object.values(bothWords)
    case 'playlistWords': return Object.values(playlistWords)
    case 'playlistLabel': return playlistLabel(input.name ?? undefined)
    case 'extrasCount': return extrasCount({subtitles: !!input.subtitles, cut: !!input.cut, sponsor: !!input.sponsor, chapters: !!input.chapters, inFile: !!input.inFile, beside: !!input.beside, queue: !!input.queue})
    case 'extrasLabel': return extrasLabel(input.changed)
    case 'whereAgo': return whereAgo(WORDS_NOW - input.ageMs, WORDS_NOW)
    case 'whereHolds':
      return whereHolds({all: !!input.all, spaces: input.spaces ?? [], playlists: input.playlists ?? [], favourites: !!input.favourites, ...(input.held !== undefined ? {files: {held: input.held, of: input.of}} : {})})
    case 'whereState':
      return whereState({state: input.state, at: input.ageMs === undefined ? undefined : WORDS_NOW - input.ageMs, done: input.done, total: input.total, reason: input.reason, unplugged: input.unplugged, count: input.count}, !!input.toCopy, WORDS_NOW)
    case 'whereSingle': return whereSingle(input.kind, input.count, input.place)
    case 'whereOnePlace': return whereOnePlace(input.thisName)
    case 'whereItemLine': return whereItemLine((input.places as Obj[]).map((place) => ({name: place.name, offline: !!place.offline})))
    case 'normalizeTags': return normalizeTags(input.raw)
    case 'sourceTags': return collectSourceTags(input.info)
    case 'scopeTags': return plain(tagsInScope(input.items, input.minItems))
    case 'newKeys': return {keys: newEntries((input.entries as string[]).map((url) => ({url})), input.seen).map((entry) => entryKey(entry.url))}
    case 'itemKey': return entryKey(input.url)
    case 'gate': return gateNew(input.count, input.confirmAbove, input.approved === true)
    case 'order': {
      const index: Record<string, number> = input.index
      return {ids: sortBySourceOrder(input.ids, (id) => index[id])}
    }
    case 'removed': return {ids: removedFromSource(input.members, new Set<string>(input.discovered))}
    case 'migratePlaylist': {
      const c = collectionFromPlaylist(input.playlist, input.space)
      return plain({id: c.id, name: c.name, kind: c.kind, space: c.space, itemIds: c.itemIds, seen: c.seen, createdAt: c.createdAt, updatedAt: c.updatedAt})
    }
    case 'home': {
      const home = homeSpaces((input.spaces as Obj[]).map(spaceOf))
      return {shown: home.shown.map((s) => s.id), more: home.more}
    }
    case 'move': return {order: shiftId(input.ids, input.id, input.forward ? 1 : -1)}
    case 'reorder':
      try {
        return {order: reorderSpaces((input.spaces as Obj[]).map(spaceOf), input.ids).map((s) => s.id)}
      } catch {
        return {error: true}
      }
    case 'canonical': return {url: canonicalSourceUrl(input.url), id: sourceIdOfCollection(input.url) ?? null}
    case 'sameSource': return {same: canonicalSourceUrl(input.a) === canonicalSourceUrl(input.b)}
    case 'route': return defaultSpaceFor(input.url, {routes: input.routes, spaces: (input.spaces as Obj[]).map(spaceOf)} as unknown as LibrarySettings)
    case 'collectionKind': return {kind: collectionKindOf(input.url), infinite: isInfiniteSource(input.url)}
    case 'defaultFollow': {
      const follow = defaultFollow(input.kind as CollectionKind, input.url, input.keepInSyncHours as 1 | 6 | 12 | 24 | 168 | undefined)
      return plain({mode: follow.mode, intervalHours: follow.intervalHours, wifiOnly: follow.wifiOnly, notify: follow.notify, confirmAbove: follow.confirmAbove})
    }
    case 'due': {
      const collections = (input.collections as Obj[]).map(
        (c): Collection => ({
          id: c.id, name: c.id, kind: c.kind ?? 'playlist', space: 'music', itemIds: [], seen: [], createdAt: 1, updatedAt: 1,
          source: c.sourceUrl ? {url: c.sourceUrl, provider: 'YouTube'} : undefined,
          follow: c.follow ? {mode: c.follow.mode, intervalHours: c.follow.intervalHours ?? 6, wifiOnly: c.follow.wifiOnly ?? true, notify: true, confirmAbove: 20,
            lastCheckedAt: c.follow.lastCheckedAt, pending: c.follow.pending} : undefined,
        }),
      )
      return {ids: collections.filter((c) => isDue(c, input.now)).map((c) => c.id)}
    }
  }
  throw new Error(`unknown spaces op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// lyrics (gui/shared/core/lyrics.ts)
// ---------------------------------------------------------------------------------------------------------------

function lyrics(input: Obj): Json {
  const query = {title: input.title, artist: input.artist, album: input.album ?? undefined, duration: input.duration ?? undefined}
  switch (input.op) {
    case 'normalize': return normalizeLyricsKey(input.value)
    case 'clean': return cleanSongTitle(input.title, input.artist)
    case 'getUrl': return lyricsExactUrl(query) ?? null
    case 'searchUrl': return lyricsFindUrl(query)
    case 'exact': return lyricsInExactAnswer(JSON.stringify(input.record)) ?? null
    case 'search': return lyricsInSearchAnswer(query, JSON.stringify(input.records)) ?? null
    case 'retry': return lyricsRetryAfterMs(input.status ?? undefined, input.attempt) ?? null
    case 'busy': return lyricsServiceBusy(input.status ?? undefined)
    case 'outcome': return lyricsOutcome(input.found, input.gaveUpOnBusy)
    case 'isTimed': return lyricsAreTimed(input.text)
    case 'preferTimed': return preferTimedLyrics(input.exact, input.found) ?? null
    case 'outcomeWords': return lyricsOutcomeText(input.outcome)
    case 'findWords': return findLyricsWords
  }
  throw new Error(`unknown lyrics op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// choice (the desktop preview's adapter, gui/shared/core/choice.ts)
// ---------------------------------------------------------------------------------------------------------------

function choiceMedia(input: Obj): ChoiceSource {
  return {
    duration: input.media.duration ?? undefined,
    video: ((input.media.video ?? []) as Obj[]).map((v) => ({
      id: v.id, height: v.height ?? 0, width: v.width, fps: v.fps ?? undefined, hdr: !!v.hdr, codec: v.codec ?? 'other', ext: 'mp4', formatId: v.formatId ?? v.id,
      hasAudio: !!v.hasAudio, size: v.size ?? undefined, sizeApprox: !!v.sizeApprox,
    })),
    audio: ((input.media.audio ?? []) as Obj[]).map((a) => ({
      id: a.formatId, formatId: a.formatId, codec: a.codec ?? 'other', ext: 'm4a', size: a.size ?? undefined, sizeApprox: !!a.sizeApprox,
    })),
  } as ChoiceSource
}

function choicePicks(input: Obj): ChoicePicks {
  const c = input.choice ?? {}
  return {mode: c.mode ?? 'video', videoId: c.videoId ?? undefined, container: c.container ?? 'mp4', target: c.target ?? 'mp3', quality: c.quality ?? '320', audioTrack: c.audioTrack ?? undefined, audioSource: c.audioSource ?? undefined}
}

function choice(input: Obj): Json {
  switch (input.op) {
    case 'offer': return {containers: containerOffer(choiceMedia(input), choicePicks(input)).map((item) => ({...item, reason: item.reason ?? null}))}
    case 'size': {
      const {size, approx} = sizeOf(choiceMedia(input), choicePicks(input))
      return {bytes: size ?? null, approx}
    }
    case 'opening': {
      const start = input.start
      return plain(openingPicks(choiceMedia(input), {preset: start.preset, url: start.url ?? undefined, music: !!start.music, youtubeMusic: start.youtubeMusic ?? 'audio'}))
    }
    case 'forList': return plain(presetFormat(input.preset))
    case 'applyPreset': return plain(picksForPreset(choiceMedia(input), choicePicks(input), input.preset))
    case 'pickTarget': return plain(picksForTarget(choicePicks(input), input.target))
    case 'modes': return modesOf(choiceMedia(input))
    case 'request': return plain(requestOf(choiceMedia(input), choicePicks(input)))
    case 'videoLabel': return videoLabel({height: input.height, fps: input.fps ?? undefined})
    case 'pickVideo': {
      const picks = choicePicks(input)
      return plain({...picks, videoId: input.videoId, container: containerAfterPick(choiceMedia(input), picks, input.videoId)})
    }
  }
  throw new Error(`unknown choice op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// after (what happens when a download finishes, gui/shared/core/after.ts)
// ---------------------------------------------------------------------------------------------------------------

function after(input: Obj): Json {
  if (input.op === 'plan') return {steps: plain(planAfter(input))}
  throw new Error(`unknown after op ${input.op}`)
}

// ---------------------------------------------------------------------------------------------------------------
// Driver
// ---------------------------------------------------------------------------------------------------------------

const handlers: Record<Category, (input: Obj) => Json | Promise<Json>> = {formats, args, progress, errors, providers, music, dedupe, versions, spaces, playback, lyrics, choice, after}

function* fixtureFiles(dir: string): Generator<string> {
  for (const entry of fs.readdirSync(dir, {withFileTypes: true}).sort((a, b) => a.name.localeCompare(b.name))) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (entry.name !== 'samples') yield* fixtureFiles(full)
    } else if (entry.name.endsWith('.json')) yield full
  }
}

test('every fixture category exists and has cases', () => {
  for (const category of categories) {
    const files = [...fixtureFiles(path.join(root, category))]
    assert.ok(files.length > 0, `fixtures/engine/${category} has no fixture files`)
    const total = files.reduce((sum, file) => sum + (JSON.parse(fs.readFileSync(file, 'utf8')) as FixtureFile).cases.length, 0)
    assert.ok(total > 0, `fixtures/engine/${category} has no cases`)
  }
})

for (const category of categories) {
  for (const file of fixtureFiles(path.join(root, category))) {
    const fixture = JSON.parse(fs.readFileSync(file, 'utf8')) as FixtureFile
    const label = `${category}/${path.basename(file, '.json')}`
    const names = new Set<string>()
    for (const item of fixture.cases) {
      assert.ok(!names.has(item.name), `duplicate case name "${item.name}" in ${label}`)
      names.add(item.name)
      const skip = item.platforms && !item.platforms.includes('desktop')
      test(`${label}: ${item.name}`, {skip: skip ? 'platforms excludes desktop' : false}, async () => {
        const actual = await handlers[category](item.input)
        // FIXTURES_DUMP=1 prints what the desktop logic returns, to help write `expected` for a new case.
        if (process.env.FIXTURES_DUMP) return void console.log(`DUMP ${label}: ${item.name}\n${JSON.stringify(actual)}`)
        // No user-facing text may ever name an external tool, whatever else a case checks.
        if (category === 'errors' && item.input.op !== 'retry' && item.input.op !== 'suggestsGallery') assert.equal((actual as Obj).noToolNames, true, 'a user-facing message names an external tool')
        if (category === 'progress') assertProgress(actual, item.expected, item.name)
        else if (category === 'music') assertMusic(actual, item.expected, item.name)
        else assertSubset(actual, item.expected, item.name)
      })
    }
  }
}
