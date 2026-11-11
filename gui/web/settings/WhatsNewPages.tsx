import {useRef, useState, type KeyboardEvent, type PointerEvent, type ReactNode} from 'react'
import {navigate} from '../nav'
import {Button, IconButton} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import type {WhatsNewPage} from '../whatsNewPages'

/** The What's new dialog as pages: one picture and paragraph per top change, then the full notes as the last page. */
export function WhatsNewPages({
  pages,
  notesBody,
  version,
  close,
}: {
  pages: WhatsNewPage[]
  notesBody: ReactNode
  version: string
  close: () => void
}) {
  const last = pages.length
  const [index, setIndex] = useState(0)
  const [forward, setForward] = useState(true)
  const swipe = useRef<number | null>(null)
  const go = (to: number) => {
    const next = Math.max(0, Math.min(last, to))
    if (next === index) return
    setForward(next > index)
    setIndex(next)
  }
  const onKeyDown = (event: KeyboardEvent) => {
    if (event.key !== 'ArrowRight' && event.key !== 'ArrowLeft') return
    // Arrows keep their meaning inside text fields and the scrolling notes.
    if ((event.target as HTMLElement).closest('input, textarea, select')) return
    event.preventDefault()
    go(index + (event.key === 'ArrowRight' ? 1 : -1))
  }
  const onPointerUp = (event: PointerEvent) => {
    const from = swipe.current
    swipe.current = null
    if (from !== null && Math.abs(event.clientX - from) > 40) go(index + (event.clientX < from ? 1 : -1))
  }
  const page = index < last ? pages[index] : undefined
  const eyebrow = `What’s new in ${version.replace(/^v/i, '')} · ${page ? `${index + 1} of ${last}` : 'Full list'}`

  return (
    <Dialog
      title={eyebrow}
      labelledBy="release-notes-title"
      className="notes-dialog wn-dialog"
      onClose={close}
      header={
        <header className="dialog-head wn-head">
          <div className="dialog-eyebrow" id="release-notes-title">
            {eyebrow}
          </div>
        </header>
      }
    >
      <div className="wn" onKeyDown={onKeyDown}>
        <div
          className="wn-stage"
          data-dir={forward ? 'next' : 'prev'}
          onPointerDown={(event) => {
            swipe.current = (event.target as HTMLElement).closest('.release-markdown') ? null : event.clientX
          }}
          onPointerUp={onPointerUp}
          onPointerCancel={() => {
            swipe.current = null
          }}
        >
          {page ? (
            <section className="wn-page" key={page.id}>
              {page.image && (
                <div className="wn-pic">
                  <img src={page.image} alt="" draggable={false} />
                </div>
              )}
              <div className="wn-copy">
                <h2 className="wn-title">{page.title}</h2>
                <p className="wn-text">{page.text}</p>
              </div>
            </section>
          ) : (
            <section className="wn-page wn-notes" key="notes">
              <h2 className="wn-title wn-notes-title">Everything in {version.replace(/^v/i, '')}</h2>
              {notesBody}
            </section>
          )}
        </div>
        <footer className="wn-foot">
          <IconButton icon="chevronLeft" label="Previous page" onClick={() => go(index - 1)} disabled={index === 0} />
          <div className="wn-dots">
            {[...pages.map((_, at) => `Page ${at + 1}`), 'Full list'].map((label, at) => (
              <button
                key={label}
                type="button"
                className={`wn-dot${at === index ? ' is-on' : ''}`}
                aria-label={label}
                aria-current={at === index ? 'step' : undefined}
                onClick={() => go(at)}
              />
            ))}
          </div>
          {page ? (
            <>
              <Button variant="ghost" onClick={() => go(last)}>
                Full list
              </Button>
              <Button variant="primary" onClick={() => go(index + 1)} autoFocus>
                Next
              </Button>
            </>
          ) : (
            <>
              <Button
                variant="ghost"
                icon="book"
                onClick={() => {
                  close()
                  navigate('Settings', 'guide')
                }}
              >
                See all features
              </Button>
              <Button variant="primary" onClick={close} autoFocus>
                Done
              </Button>
            </>
          )}
        </footer>
      </div>
    </Dialog>
  )
}
