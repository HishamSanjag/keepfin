import type {MediaInfo} from '../../shared/contracts'
import {Icon} from '../ui/Icon'
import {Thumbnail} from '../ui/Thumbnail'
import {galleryChosen, type Choice} from './choice'

/** The photos of a gallery post: every item in a grid, all chosen, and a click leaves one out. */
export function GalleryPreview({media, choice, onChange}: {media: MediaInfo; choice: Choice; onChange: (next: Choice) => void}) {
  const gallery = media.gallery
  if (!gallery) return null
  const out = new Set(choice.leftOut)
  const chosen = galleryChosen(media, choice).length
  const toggle = (position: number) => {
    const next = new Set(out)
    if (!next.delete(position)) next.add(position)
    onChange({...choice, leftOut: [...next]})
  }
  return (
    <div className="picker">
      <p className="picker-note" role="status">
        {chosen.toLocaleString()} of {gallery.count.toLocaleString()} chosen. {chosen ? 'Click a photo to leave it out.' : 'Click a photo to choose it.'}
        {gallery.truncated ? ' The listing was capped, so the post may contain more.' : ''}
      </p>
      <div className="gallery-grid">
        {gallery.items.map((item) => (
          <button
            key={item.position}
            type="button"
            className={`gallery-tile${out.has(item.position) ? ' is-out' : ''}`}
            aria-pressed={!out.has(item.position)}
            aria-label={`${item.photo ? 'Photo' : 'Video'} ${item.position}`}
            onClick={() => toggle(item.position)}
          >
            <Thumbnail url={item.photo ? item.url : undefined} kind="gallery" lazy />
            {!item.photo && (
              <span className="gallery-play">
                <Icon name="play" size={14} />
              </span>
            )}
            <span className="gallery-check">{!out.has(item.position) && <Icon name="check" size={14} />}</span>
          </button>
        ))}
      </div>
    </div>
  )
}
