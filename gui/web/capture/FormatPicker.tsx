import type {MediaInfo} from '../../shared/contracts'
import {Thumbnail} from '../ui/Thumbnail'

/** The photos of a gallery post: a grid of previews and how many items will be saved. */
export function GalleryPreview({media}: {media: MediaInfo}) {
  const gallery = media.gallery
  if (!gallery) return null
  return (
    <div className="picker">
      <div className="gallery-grid">
        {gallery.previews.slice(0, 12).map((url, index) => (
          <Thumbnail key={url} url={url} kind="gallery" lazy label={`Item ${index + 1}`} />
        ))}
      </div>
      <p className="picker-note">
        {gallery.count.toLocaleString()} {gallery.count === 1 ? 'item' : 'items'} will be saved in original quality
        {gallery.truncated ? '. The listing was capped, so the post may contain more.' : '.'}
      </p>
    </div>
  )
}
