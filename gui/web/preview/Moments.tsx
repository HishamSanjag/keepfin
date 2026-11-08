// The two moments around a preview: the link is being read, and the link could not be read. These are plain functions that return
// the window, not components, so the window stays the same one as the link goes from being read to being shown.
import {useState} from 'react'
import {siteOf} from '../../shared/core/signin'
import {native} from '../bridge'
import {navigate} from '../nav'
import {signInTo} from '../signin'
import {Icon} from '../ui/Icon'
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

/** Approved design A: where the formats or photos would be, the site says it shows this only to signed-in people. */
function SignInNeeded({site, onSignedIn, onNotNow}: {site: {key: string; name: string}; onSignedIn: () => void; onNotNow: () => void}) {
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  async function signIn() {
    setBusy(true)
    setMessage('')
    const outcome = await signInTo(site.key)
    setBusy(false)
    if (outcome.signedIn) onSignedIn()
    else setMessage(outcome.message ?? `Keepfin is not signed in to ${site.name}.`)
  }
  return (
    <div className="preview-problem preview-signin" role="alert">
      <span className="preview-lock" aria-hidden="true">
        <Icon name="lock" size={28} />
      </span>
      <p className="preview-signin-title">{site.name} shows this post only to signed-in people.</p>
      <p>Sign in once and Keepfin uses it for {site.name} links from now on. Your password stays with {site.name}.</p>
      {message && (
        <p className="field-error" role="status">
          {message}
        </p>
      )}
      <div className="preview-problem-actions">
        <Button variant="primary" icon="key" loading={busy} onClick={() => void signIn()}>
          Sign in to {site.name}
        </Button>
        <Button variant="ghost" disabled={busy} onClick={onNotNow}>
          Not now
        </Button>
      </div>
    </div>
  )
}

export function problemWindow({
  entry,
  open,
  onClose,
  onRetry,
  signInDeclined,
  onNotNow,
}: {
  entry: CaptureEntry
  open: boolean
  onClose: () => void
  onRetry: () => void
  /** The person chose "Not now" for this link: the plain message is shown. */
  signInDeclined?: boolean
  onNotNow?: () => void
}) {
  const site = native && needsSignIn(entry.error) ? siteOf(entry.url) : undefined
  if (site && !signInDeclined) {
    // Download stays off until the link can be read.
    return (
      <PreviewWindow
        open={open}
        onClose={onClose}
        title={`${site.name} link`}
        facts={shortLink(entry.url)}
        footer={
          <>
            <span className="foot-gap" />
            <Button variant="primary" size="lg" icon="download" disabled className="download-btn">
              Download
            </Button>
          </>
        }
      >
        <SignInNeeded site={site} onSignedIn={onRetry} onNotNow={() => onNotNow?.()} />
      </PreviewWindow>
    )
  }
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
