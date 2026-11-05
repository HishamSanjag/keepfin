import {memo, useEffect, useRef, useState} from 'react'
import {formatDuration} from '../format'
import {loadThumb, peekThumb, thumbKey} from '../thumbs'
import {Icon} from './Icon'

type Props = {
  jobId?: string
  url?: string
  ready?: boolean
  kind?: 'video' | 'audio' | 'gallery'
  duration?: number
  label?: string
  /** Defer the artwork request until the tile scrolls near the viewport. */
  lazy?: boolean
  className?: string
}

/** Memoized on primitive props: progress updates never touch it, so the <img> stays put. */
export const Thumbnail = memo(function Thumbnail({
  jobId,
  url,
  ready,
  kind = 'video',
  duration,
  label,
  lazy,
  className,
}: Props) {
  const key = thumbKey({jobId, url, ready})
  const [state, setState] = useState<{key: string; image: string | null | undefined}>(() => ({
    key,
    image: peekThumb(key),
  }))
  const box = useRef<HTMLDivElement>(null)
  const current = state.key === key ? state.image : peekThumb(key)
  // Keep the previous artwork visible while a refreshed key (e.g. completed file frame) loads.
  const last = useRef<string | undefined>(undefined)
  if (current) last.current = current
  const image = current === undefined ? last.current : current || undefined

  useEffect(() => {
    if (!key || peekThumb(key) !== undefined) {
      if (state.key !== key) setState({key, image: peekThumb(key)})
      return
    }
    let alive = true
    let observer: IntersectionObserver | undefined
    const start = () =>
      loadThumb({jobId, url, ready}).then((image) => {
        if (alive) setState({key, image})
      })
    if (lazy && box.current && 'IntersectionObserver' in window) {
      observer = new IntersectionObserver(
        (entries) => {
          if (entries.some((entry) => entry.isIntersecting)) {
            observer?.disconnect()
            void start()
          }
        },
        {rootMargin: '400px'},
      )
      observer.observe(box.current)
    } else void start()
    return () => {
      alive = false
      observer?.disconnect()
    }
  }, [key])

  return (
    <div ref={box} className={`thumb thumb-${kind}${className ? ` ${className}` : ''}`}>
      {image ? (
        <img src={image} alt={label ? `Artwork for ${label}` : ''} draggable={false} decoding="async" />
      ) : (
        <Icon name={kind === 'audio' ? 'music' : kind === 'gallery' ? 'image' : 'video'} size={22} />
      )}
      {!!duration && <span className="thumb-duration">{formatDuration(duration)}</span>}
    </div>
  )
})
