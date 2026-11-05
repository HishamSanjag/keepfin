import {memo, useState} from 'react'
import {spaceColorOf} from '../../shared/library'
import type {LibraryItem} from '../../shared/library'
import {artUrl} from '../media'
import {Icon} from '../ui/Icon'
import {spaceById} from './libStore'
import {progressFraction} from './model'

const glyph: Record<string, string> = {audio: 'music', video: 'video', image: 'image', other: 'stack'}

/** Up to two initials from a title ("Glass Harbour" gives GH); empty when the title has no letters or digits. */
export function initialsOf(title: string | undefined) {
  const words = (title || '').split(' ').filter((word) => /[A-Za-z0-9À-￿]/.test(word))
  const letters = words.slice(0, 2).map((word) => (word.match(/[A-Za-z0-9À-￿]/) || [''])[0].toUpperCase())
  return letters.join('')
}

/**
 * Cover art or poster for an item id. Without art (or when it fails to load) it shows a designed cover: a calm gradient from
 * the item's space colour with the title's initials, or the type glyph when the cover is small or the title unknown.
 */
export const Art = memo(function Art({
  id,
  hasArt,
  kind,
  label,
  className = '',
  size = 22,
  space,
}: {
  id?: string
  hasArt?: boolean
  kind: LibraryItem['kind']
  label?: string
  className?: string
  size?: number
  /** The space the item lives in; its colour tints the fallback cover. */
  space?: string
}) {
  const [failed, setFailed] = useState(false)
  const show = !!id && hasArt && !failed
  if (show)
    return (
      <div className={`art art-${kind} ${className}`}>
        <img src={artUrl(id!)} alt={label ? `Artwork for ${label}` : ''} loading="lazy" decoding="async" draggable={false} onError={() => setFailed(true)} />
      </div>
    )
  const initials = size >= 26 ? initialsOf(label) : ''
  const color = spaceColorOf(spaceById(space) ?? {id: space || id || '', color: undefined})
  return (
    <div className={`art art-${kind} art-fallback sp-${color} ${className}`}>
      {initials ? (
        <span className="art-initials" aria-hidden="true">
          {initials}
        </span>
      ) : (
        <Icon name={glyph[kind] || 'stack'} size={size} />
      )}
    </div>
  )
})

/** Up to four covers as a 2x2 mosaic (folders, playlists); one cover fills the tile. */
export const Mosaic = memo(function Mosaic({ids, kind = 'audio', className = ''}: {ids: string[]; kind?: LibraryItem['kind']; className?: string}) {
  if (ids.length < 2) return <Art id={ids[0]} hasArt={ids.length > 0} kind={kind} className={className} />
  return (
    <div className={`art art-mosaic ${className}`}>
      {ids.slice(0, 4).map((id) => (
        <Art key={id} id={id} hasArt kind={kind} />
      ))}
    </div>
  )
})

export function ProgressTick({item}: {item: LibraryItem}) {
  const fraction = progressFraction(item)
  if (!fraction || item.watched) return null
  return (
    <span className="poster-progress" role="progressbar" aria-label="Watched" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(fraction * 100)}>
      <span style={{transform: `scaleX(${fraction})`}} />
    </span>
  )
}
