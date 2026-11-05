import {useEffect, useState} from 'react'
import {native} from '../bridge'
import {extractLinks} from '../format'
import {Button, IconButton, Kbd, Spinner} from '../ui/controls'
import {Icon, Mark} from '../ui/Icon'
import {HowItWorks} from './HowItWorks'
import {maxBatch, type CaptureModel} from './useCapture'

function Elapsed({since}: {since: number}) {
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.round((now - since) / 1000))
  return <span className="tabular">{seconds}s</span>
}

export function CaptureBar({capture: c, review}: {capture: CaptureModel; review: {open: () => void}}) {
  const links = extractLinks(c.input)
  const lines = Math.min(5, Math.max(1, c.input.split('\n').length))
  const waiting = c.entries.filter((entry) => entry.state === 'waiting' || entry.state === 'inspecting').length
  const [explain, setExplain] = useState(false)
  /** Reading a link asked for by hand also brings the review window back if it was put away. */
  function inspectAndShow() {
    c.inspect()
    review.open()
  }
  return (
    <section className="capture" aria-label="Add a link">
      <div className={`capture-field${c.inputError ? ' has-error' : ''}${c.inspecting ? ' is-busy' : ''}`}>
        <span className="capture-mark">
          <Mark size={24} />
        </span>
        <label htmlFor="capture-input" className="sr-only">
          Media link
        </label>
        <textarea
          id="capture-input"
          ref={c.inputRef}
          rows={lines}
          value={c.input}
          spellCheck={false}
          autoComplete="off"
          aria-invalid={!!c.inputError}
          aria-describedby="capture-help"
          placeholder="Paste a video, song, playlist or post link"
          onChange={(event) => c.edit(event.target.value)}
          onPaste={() => c.pasted()}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              inspectAndShow()
            } else if (event.key === 'Escape' && (c.inspecting || (c.input && !c.entries.length))) {
              event.preventDefault()
              c.inspecting ? c.cancel() : c.dismiss()
            }
          }}
        />
        <div className="capture-actions">
          {c.inspecting ? (
            <span className="capture-status" role="status">
              <Spinner />
              <span>
                {links.length > 1 ? `Inspecting ${waiting} of ${c.entries.length}` : 'Reading formats'} ·{' '}
                <Elapsed since={c.startedAt} />
              </span>
            </span>
          ) : (
            !c.input && (
              <span className="capture-hint" aria-hidden="true">
                <Kbd>Ctrl</Kbd>
                <Kbd>L</Kbd>
              </span>
            )
          )}
          {c.inspecting ? (
            <Button size="sm" variant="ghost" onClick={c.cancel}>
              Cancel
            </Button>
          ) : c.input ? (
            <>
              {c.entries.length ? (
                // always there, so focus can come back to it when the window is put away
                <Button size="sm" variant="primary" onClick={review.open}>
                  Open review
                </Button>
              ) : (
                <Button size="sm" variant="primary" onClick={inspectAndShow} disabled={!native}>
                  Inspect
                </Button>
              )}
              <IconButton icon="close" label="Clear link" size="sm" onClick={c.dismiss} />
            </>
          ) : (
            <Button size="sm" icon="paste" onClick={c.pasteFromClipboard} disabled={!native}>
              Paste
            </Button>
          )}
        </div>
      </div>
      {/* One short line under the field: an error or the batch note when there is one, otherwise what works. */}
      {c.inputError || links.length > 1 ? (
        <p id="capture-help" className={c.inputError ? 'capture-error' : 'capture-help'} role={c.inputError ? 'alert' : undefined}>
          {c.inputError || `${links.length} links · each is inspected separately (up to ${maxBatch}).`}
        </p>
      ) : (
        <p id="capture-help" className="works-with">
          <span className="works-with-text">
            Videos, songs, playlists and posts from YouTube, Spotify, Apple Music, SoundCloud, TikTok, Instagram and 1,000+ more sites
          </span>
          <button type="button" className="how-open" aria-haspopup="dialog" onClick={() => setExplain(true)}>
            <Icon name="info" size={14} />
            How it works
          </button>
        </p>
      )}
      {explain && <HowItWorks onClose={() => setExplain(false)} />}
    </section>
  )
}
