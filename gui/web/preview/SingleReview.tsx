// The preview of one link: a video, a song, a song you already have, a photo post, and the two moments around them
// (reading the link, and a link that could not be read). One window for all of them, so it never closes and reopens as the link is read.
import {useState} from 'react'
import type {MediaInfo} from '../../shared/contracts'
import {bothWords, isVideoSongKind, musicVideoWords} from '../../shared/library'
import {prefersAudio} from '../../shared/providers'
import {native} from '../bridge'
import {GalleryPreview} from '../capture/FormatPicker'
import {MatchLine} from '../capture/MatchLine'
import {SaveTo, useDefaultSpace} from '../capture/SaveTo'
import {availableTabs, estimateLabel, formatRequest, galleryChosen, galleryChosenLabel, type Choice} from '../capture/choice'
import type {CaptureEntry, CaptureModel} from '../capture/useCapture'
import {DownloadOn} from '../devices/DownloadOn'
import {formatDuration, plural} from '../format'
import {getLibItem, spaceName, useLibraryEnabled, useLibrarySettings} from '../library/libStore'
import {viewsLabel} from '../library/MusicVideoDialogs'
import {playById} from '../player/play'
import {useMeta} from '../store'
import {Button, InlineAlert, Segmented, Spinner} from '../ui/controls'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {AddToPlaylist} from './AddToPlaylist'
import {ExtrasGroups} from './Extras'
import {extrasChanged, validateOptions} from './extrasModel'
import {problemWindow, readingWindow} from './Moments'
import {PreviewWindow} from './PreviewWindow'
import {SizeList} from './SizeList'
import {useSongOfLink, useSongVideo, type SongVideo} from './songVideo'

type Mode = 'video' | 'audio' | 'both' | 'gallery'

function factsOf(media: MediaInfo) {
  const best = media.video[0]
  return [
    media.provider,
    media.isLive && 'Live',
    best && best.height >= 2160 && (best.height >= 4320 ? '8K' : '4K'),
    media.video.some((option) => option.hdr) && 'HDR',
    media.gallery && plural(media.gallery.count, 'item'),
    media.uploader,
    media.music?.album,
    media.music?.releaseYear ? String(media.music.releaseYear) : '',
    formatDuration(media.duration),
    media.chapters ? plural(media.chapters, 'chapter') : '',
    media.subtitles.length ? plural(new Set(media.subtitles.map((track) => track.lang)).size, 'subtitle language') : '',
  ]
    .filter(Boolean)
    .join(' · ')
}

/** The line above the sizes when Both is chosen and a video was found: its picture, title, channel, views and length. */
function FoundVideo({video}: {video: Extract<SongVideo, {state: 'found'}>}) {
  const facts = [video.channel, video.views ? viewsLabel(video.views) : '', video.durationSec ? formatDuration(video.durationSec) : ''].filter(Boolean).join(' · ')
  return (
    <div className="found-video">
      <Thumbnail url={`https://i.ytimg.com/vi/${video.videoId}/mqdefault.jpg`} kind="video" label={video.title} className="found-video-cover" />
      <div>
        <strong>{video.title}</strong>
        {facts && <div className="muted small">{facts}</div>}
      </div>
    </div>
  )
}

function Note({children, action}: {children: string; action?: {label: string; onClick: () => void}}) {
  return (
    <div className="preview-note" role="status">
      <span>{children}</span>
      {action && (
        <Button size="sm" icon="play" onClick={action.onClick}>
          {action.label}
        </Button>
      )}
    </div>
  )
}

export function SingleReview({capture: c, entry, open, onClose}: {capture: CaptureModel; entry: CaptureEntry; open: boolean; onClose: () => void}) {
  const meta = useMeta()
  const [notNow, setNotNow] = useState<string[]>([])
  const libraryOn = useLibraryEnabled()
  const librarySettings = useLibrarySettings()
  const media = entry.media
  const choice = entry.choice
  const tabs = media ? availableTabs(media) : []
  const song = useSongVideo(c, media, native && libraryOn && tabs.includes('audio'))
  const bothOn = !!media && choice?.tab === 'audio' && c.keepVideo?.url === media.url
  const mode: Mode = choice?.tab === 'gallery' ? 'gallery' : choice?.tab === 'video' ? 'video' : bothOn ? 'both' : 'audio'
  // a video of a song you have is added to that song
  const own = useSongOfLink(media?.url, native && libraryOn && !!media && (mode === 'video' || mode === 'both'))
  const defaultSpace = useDefaultSpace(media?.url)
  if (entry.state === 'waiting' || entry.state === 'inspecting') return readingWindow({entry, open, onClose, onCancel: c.cancel})
  if (!media || !choice) return problemWindow({entry, open, onClose, onRetry: () => c.retry(entry.url), signInDeclined: notNow.includes(entry.url), onNotNow: () => setNotNow((list) => [...list, entry.url])})

  const found = c.existing[media.url]
  const setChoice = (next: Choice) => c.setChoice(entry.url, next)
  const optionError = validateOptions(c.options, media.duration)
  const size = estimateLabel(media, choice)
  const adding = entry.state === 'adding'
  const target = librarySettings?.spaces.find((space) => space.id === (c.space ?? defaultSpace))
  const keptAsSong = libraryOn && mode === 'video' && isVideoSongKind('video', target?.kind)
  const joined = mode === 'video' || mode === 'both' ? own : null
  const downloadAnyway = !!found && !joined
  const noPhotos = choice.tab === 'gallery' && !galleryChosen(media, choice).length

  const pick = (next: Mode) => {
    if (next === 'both') {
      if (song.state !== 'found') return
      c.setKeepVideo({url: media.url, videoId: song.videoId})
      setChoice({...choice, tab: 'audio'})
      return
    }
    c.setKeepVideo(undefined)
    setChoice({...choice, tab: next})
  }
  const modes: {value: Mode; label: string; disabled?: boolean; title?: string}[] = []
  if (tabs.includes('video')) modes.push({value: 'video', label: 'Video'})
  if (tabs.includes('audio')) modes.push({value: 'audio', label: 'Audio'})
  if (song.state !== 'off') modes.push({value: 'both', label: 'Both', disabled: song.state !== 'found', title: song.state === 'looking' ? bothWords.looking : song.state === 'none' ? bothWords.none : undefined})
  if (tabs.includes('gallery') && tabs.length > 1) modes.push({value: 'gallery', label: 'Photos'})
  const photosOnly = tabs.length === 1 && tabs[0] === 'gallery'
  // a song (or a link with no picture to speak of) has a square cover, a video the wide one
  const squareCover = !media.video.length || !!media.match || media.engine === 'music' || prefersAudio(media.webpageUrl || media.url)

  const note =
    joined ? (
      <Note action={getLibItem(joined.itemId) ? {label: 'Play song', onClick: () => playById(joined.itemId)} : undefined}>
        {joined.hasVideo ? musicVideoWords.hasVideoNote : musicVideoWords.joinNote}
      </Note>
    ) : libraryOn && found ? (
      <Note action={found.itemId && getLibItem(found.itemId) ? {label: 'Play', onClick: () => playById(found.itemId!)} : undefined}>
        {`Already in ${spaceName(found.space)}. A matching recording is already saved; it is added again only if you press Download anyway.`}
      </Note>
    ) : null

  return (
    <PreviewWindow
      open={open}
      onClose={onClose}
      cover={
        <Thumbnail
          url={media.thumbnail || media.gallery?.previews[0]}
          kind={photosOnly ? 'gallery' : media.video.length ? 'video' : 'audio'}
          duration={media.duration}
          label={media.title}
          className={`preview-cover${squareCover ? ' is-square' : ''}`}
        />
      }
      title={media.title}
      facts={factsOf(media)}
      mainLabel={photosOnly ? (media.gallery ? `Photos · ${media.gallery.count}` : 'Photos') : 'What to save'}
      extras={mode === 'gallery' ? undefined : {changed: extrasChanged(c.options, meta.settings.options), content: <ExtrasGroups options={c.options} onChange={c.setOptions} duration={media.duration} subtitles={media.subtitles} chapters={media.chapters} />}}
      error={optionError}
      busy={adding}
      footer={
        <>
          {libraryOn ? (
            <>
              <SaveTo url={media.url} value={c.space} onChange={c.setSpace} />
              {mode !== 'gallery' && <AddToPlaylist value={c.playlist} onChange={c.setPlaylist} />}
            </>
          ) : (
            <span className="dest" title={meta.settings.outDir}>
              <Icon name="folder" size={15} />
              <span className="dest-path">{meta.settings.outDir}</span>
              {meta.settings.organization === 'platform' && <span className="dest-note">· by platform</span>}
            </span>
          )}
          <span className="foot-gap" />
          <DownloadOn jobs={() => [{url: media.url, format: formatRequest(media, choice), options: c.options}]} onSent={c.dismiss} disabled={!!optionError || noPhotos} />
          <Button variant="primary" size="lg" icon="download" loading={adding} disabled={!!optionError || noPhotos} onClick={() => void c.enqueue([entry])} className="download-btn">
            {downloadAnyway ? 'Download anyway' : 'Download'}
            {choice.tab === 'gallery' && media.gallery ? (
              <> {galleryChosenLabel(media, choice)}</>
            ) : (
              size && (
                <>
                  {' '}
                  <span className="btn-sep">·</span> <span className="tabular">{size}</span>
                </>
              )
            )}
          </Button>
        </>
      }
    >
      {modes.length > 1 && <Segmented<Mode> label="What to keep" value={mode} onChange={pick} options={modes} />}
      {mode === 'audio' && song.state === 'looking' && (
        <p className="preview-hint" role="status">
          <Spinner size={13} /> {bothWords.looking}
        </p>
      )}
      {mode === 'audio' && song.state === 'none' && <p className="preview-hint">{bothWords.none}</p>}
      {media.match && <MatchLine match={media.match} />}
      {media.playlist && (
        <div className="preview-note">
          <span>
            Part of {media.playlist.title ? <strong>{media.playlist.title}</strong> : 'a playlist'}
          </span>
          <Button size="sm" onClick={() => c.openCollection(media.playlist!.url, media.playlist!.title)}>
            Open whole playlist
          </Button>
        </div>
      )}
      {note}
      {mode === 'both' && song.state === 'found' && (
        <>
          {!song.own && <FoundVideo video={song} />}
          <p className="preview-hint">{song.own ? bothWords.link : bothWords.found}</p>
        </>
      )}
      {mode === 'gallery' ? <GalleryPreview media={media} choice={choice} onChange={setChoice} /> : <SizeList key={media.url} media={media} choice={choice} onChange={setChoice} />}
      {keptAsSong && !joined && <p className="preview-hint">Kept in {target?.name ?? 'Music'} as a song you can also watch: one file with the picture and the sound.</p>}
      {entry.error && <InlineAlert title="Couldn’t add this download">{entry.error}</InlineAlert>}
    </PreviewWindow>
  )
}
