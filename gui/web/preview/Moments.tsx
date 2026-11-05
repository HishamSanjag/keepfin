// The two moments around a preview: the link is being read, and the link could not be read. These are plain functions that return
// the window, not components, so the window stays the same one as the link goes from being read to being shown.
import {navigate} from '../nav'
import type {CaptureEntry} from '../capture/useCapture'
import {hostOf} from '../format'
import {Button} from '../ui/controls'
import {PreviewWindow} from './PreviewWindow'

const needsEngine = (message = '') => /update (the )?(download )?engine|outdated|throttl/i.test(message)
const needsSignIn = (message = '') => /sign[ -]?in|log ?in/i.test(message)

/** What can be said of a link without reading it: where it is from and what it points to. */
function shortLink(url: string) {
  try {
    const parsed = new URL(url)
    const path = `${parsed.hostname.replace(/^www\./, '')}${parsed.pathname === '/' ? '' : parsed.pathname}${parsed.search}`
    return path.length > 70 ? `${path.slice(0, 69)}…` : path
  } catch {
    return url
  }
}

export function readingWindow({entry, open, onClose, onCancel}: {entry: CaptureEntry; open: boolean; onClose: () => void; onCancel: () => void}) {
  return (
    <PreviewWindow
      open={open}
      onClose={onClose}
      cover={<div className="thumb preview-cover sk" aria-hidden="true" />}
      title={`Reading formats from ${hostOf(entry.url) || 'this link'}…`}
      facts={shortLink(entry.url)}
      busy
      footer={
        <>
          <span className="foot-gap" />
          <Button variant="ghost" onClick={onCancel}>
            Cancel
          </Button>
        </>
      }
    >
      <div role="status" aria-label="Reading this link">
        {Array.from({length: 5}, (_, index) => (
          <div key={index} className="sk-row">
            <div className="sk sk-chip" />
            <div className="sk sk-line" style={{width: `${46 - index * 4}%`}} />
            <div className="sk sk-line" style={{width: '14%', marginLeft: 'auto'}} />
          </div>
        ))}
      </div>
    </PreviewWindow>
  )
}

export function problemWindow({entry, open, onClose, onRetry}: {entry: CaptureEntry; open: boolean; onClose: () => void; onRetry: () => void}) {
  return (
    <PreviewWindow open={open} onClose={onClose} title="This link could not be read" facts={shortLink(entry.url)}>
      <div className="preview-problem" role="alert">
        <p>{entry.error}</p>
        <div className="preview-problem-actions">
          {needsEngine(entry.error) && (
            <Button variant="ghost" onClick={() => navigate('Settings', 'updates')}>
              Engine settings
            </Button>
          )}
          <Button icon="retry" onClick={onRetry}>
            Try again
          </Button>
          {needsSignIn(entry.error) && (
            <Button variant="primary" onClick={() => navigate('Settings', 'accounts')}>
              Sign in
            </Button>
          )}
        </div>
      </div>
    </PreviewWindow>
  )
}
