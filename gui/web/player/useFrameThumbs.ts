// Seek-bar preview frames (D7): a hidden, muted second <video> on the same source, created on the first hover.
// The latest request wins, one seek is in flight at a time, frames are 240 px wide and cached per 2 s bucket (LRU).
import {useEffect, useRef} from 'react'
import {Lru, thumbBucket} from './videoLogic'

// drawn a little larger than shown (176 px) so the frame stays sharp
const width = 240
type Frame = HTMLCanvasElement
type Wanted = {bucket: number; time: number; deliver: (frame: Frame | undefined) => void}

export class FrameThumbs {
  private video?: HTMLVideoElement
  private cache = new Lru<number, Frame>(120)
  private wanted?: Wanted
  private busy = false
  private flying?: Wanted
  private ready = false
  private failed = false
  private gone = false
  constructor(private url: string) {}

  /** Asks for the frame at `time`; `deliver` gets it (or undefined when frames are not available). Older requests are dropped. */
  request(time: number, duration: number, deliver: (frame: Frame | undefined) => void) {
    if (this.gone) return
    if (this.failed) return deliver(undefined)
    const bucket = thumbBucket(time)
    const hit = this.cache.get(bucket)
    if (hit) {
      this.wanted = undefined
      return deliver(hit)
    }
    // the middle of the bucket, kept inside the video
    this.wanted = {bucket, time: Math.min(bucket * 2 + 1, Math.max(0, (duration || Infinity) - 0.1)), deliver}
    this.start()
  }

  private start() {
    if (!this.video) {
      const video = document.createElement('video')
      video.muted = true
      video.preload = 'auto'
      video.crossOrigin = 'anonymous'
      video.playsInline = true
      video.addEventListener('loadeddata', () => {
        this.ready = true
        this.next()
      })
      video.addEventListener('seeked', () => this.drawn())
      video.addEventListener('error', () => {
        this.failed = true
        const wanted = this.wanted
        this.wanted = undefined
        this.busy = false
        wanted?.deliver(undefined)
      })
      video.src = this.url
      this.video = video
    }
    this.next()
  }

  private next() {
    const video = this.video
    const wanted = this.wanted
    if (!video || !wanted || this.busy || !this.ready || this.failed) return
    this.busy = true
    this.flying = wanted
    video.currentTime = wanted.time
  }

  private drawn() {
    const video = this.video
    if (!video || !this.busy) return
    this.busy = false
    const bucket = this.flying?.bucket ?? thumbBucket(video.currentTime)
    let frame: Frame | undefined
    if (video.videoWidth) {
      frame = document.createElement('canvas')
      frame.width = width
      frame.height = Math.max(1, Math.round((width * video.videoHeight) / video.videoWidth))
      frame.getContext('2d')?.drawImage(video, 0, 0, frame.width, frame.height)
      this.cache.set(bucket, frame)
    }
    const wanted = this.wanted
    if (wanted && wanted.bucket === bucket) {
      this.wanted = undefined
      wanted.deliver(frame)
    } else this.next()
  }

  dispose() {
    this.gone = true
    this.wanted = undefined
    this.cache.clear()
    const video = this.video
    if (video) {
      video.pause()
      video.removeAttribute('src')
      video.load()
    }
    this.video = undefined
  }
}

/** One FrameThumbs per source, disposed with the player. Nothing is created until the first request. */
export function useFrameThumbs(url: string | undefined) {
  const thumbs = useRef<FrameThumbs | undefined>(undefined)
  const current = useRef(url)
  current.current = url
  useEffect(
    () => () => {
      thumbs.current?.dispose()
      thumbs.current = undefined
    },
    [url],
  )
  return (time: number, duration: number, deliver: (frame: HTMLCanvasElement | undefined) => void) => {
    const source = current.current
    if (!source) return deliver(undefined)
    thumbs.current ??= new FrameThumbs(source)
    thumbs.current.request(time, duration, deliver)
  }
}
