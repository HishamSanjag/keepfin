// Which preview the capture state calls for. The caller owns whether the window is open: closing it only puts it away, the link stays
// in the field and the review is kept (so a playlist keeps its place); Clear in the field is what discards it.
import {ListReview} from './ListReview'
import {LinksReview} from './LinksReview'
import {SingleReview} from './SingleReview'
import type {CaptureModel} from '../capture/useCapture'

export function Preview({capture: c, open, onClose}: {capture: CaptureModel; open: boolean; onClose: () => void}) {
  if (c.entries.length > 1) return <LinksReview capture={c} open={open} onClose={onClose} />
  const single = c.entries[0]
  if (!single) return null
  if (single.state === 'collection')
    return <ListReview key={single.collection!.url} source={single.collection!} initialSpace={c.space} open={open} onClose={onClose} onDone={c.dismiss} />
  return <SingleReview capture={c} entry={single} open={open} onClose={onClose} />
}
