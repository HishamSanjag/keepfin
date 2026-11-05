// "Both": the song with its video kept beside it. For a music link the video is looked for in the background (the official one);
// for a YouTube link it is the link itself, nothing is searched. A look that finds nothing, or fails, only turns Both off.
import {useEffect, useState} from 'react'
import type {MediaInfo} from '../../shared/contracts'
import type {MusicVideoFound, SongOfLink} from '../../shared/library'
import {bothWords} from '../../shared/library'
import {isYouTube, prefersAudio, youTubeVideoId} from '../../shared/providers'
import {request} from '../bridge'
import {getMeta} from '../store'
import type {CaptureModel} from '../capture/useCapture'

export type SongVideo =
  /** Both does not apply to this link (not a song or a YouTube video, or the library is off). */
  | {state: 'off'}
  | {state: 'looking'}
  | {state: 'none'}
  /** `own` is the link itself (a YouTube link); otherwise it is the official video that was found. */
  | {state: 'found'; videoId: string; own: boolean; title: string; channel?: string; views?: number; durationSec?: number}

/** Finds the video Both would keep, and clears the choice when the review goes away. */
export function useSongVideo(c: CaptureModel, media: MediaInfo | undefined, enabled: boolean): SongVideo {
  const url = media?.url ?? ''
  const link = media?.webpageUrl || url
  const on = enabled && !!media
  const music = on && prefersAudio(link)
  const own = on && !music && isYouTube(link) ? youTubeVideoId(url) : null
  const [look, setLook] = useState<SongVideo>({state: 'looking'})
  useEffect(() => {
    if (!music) return
    let current = true
    setLook({state: 'looking'})
    request<MusicVideoFound>({op: 'findMusicVideo', url}).then(
      (found) => {
        if (!current) return
        if ('none' in found) return setLook({state: 'none'})
        setLook({state: 'found', own: false, videoId: found.videoId, title: found.title, channel: found.channel, views: found.views, durationSec: found.durationSec})
        // the setting "Keep music videos automatically" starts with Both chosen; it can still be changed
        if (getMeta().settings.musicVideos) c.setKeepVideo({url, videoId: found.videoId})
      },
      () => current && setLook({state: 'none'}),
    )
    return () => {
      current = false
    }
  }, [url, music])
  // the choice belongs to this review: it goes away with it
  useEffect(() => () => c.setKeepVideo(undefined), [url])
  if (music) return look
  if (own) return {state: 'found', own: true, videoId: own, title: media?.title ?? ''}
  return {state: 'off'}
}

/** The song you already have behind [url] (any space), looked up while [active]; null when it matches none. */
export function useSongOfLink(url: string | undefined, active: boolean): SongOfLink | null {
  const [song, setSong] = useState<SongOfLink | null>(null)
  useEffect(() => {
    setSong(null)
    if (!active || !url) return
    let current = true
    request<SongOfLink | null>({op: 'songOfLink', url}).then(
      (found) => current && setSong(found),
      () => undefined,
    )
    return () => {
      current = false
    }
  }, [url, active])
  return song
}
