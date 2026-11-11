import Markdown from 'react-markdown'
import rehypeRaw from 'rehype-raw'
import rehypeSanitize from 'rehype-sanitize'
import {native, request} from '../bridge'
import {navigate} from '../nav'
import {Button} from '../ui/controls'
import {Dialog} from '../ui/Dialog'
import {whatsNewPages} from '../whatsNewContent'
import {WhatsNewPages} from './WhatsNewPages'

/** Sanitized markdown body shared by the What's new dialog and the update popover. */
export function ReleaseMarkdown({notes, onError, compact}: {notes: string; onError: (message: string) => void; compact?: boolean}) {
  return (
    <div className={`release-markdown${compact ? ' compact' : ''}`} tabIndex={0} aria-label="Release notes content">
        {notes.trim() ? (
          <Markdown
            rehypePlugins={[rehypeRaw, rehypeSanitize]}
            allowedElements={[
              'p',
              'h1',
              'h2',
              'h3',
              'h4',
              'h5',
              'h6',
              'ul',
              'ol',
              'li',
              'strong',
              'em',
              'del',
              'code',
              'pre',
              'blockquote',
              'hr',
              'a',
              'br',
            ]}
            unwrapDisallowed
            urlTransform={(url) => {
              try {
                const parsed = new URL(url)
                return parsed.protocol === 'https:' &&
                  !parsed.username &&
                  !parsed.password
                  ? parsed.href
                  : ''
              } catch {
                return ''
              }
            }}
            components={{
              a: ({href, children}) =>
                href ? (
                  <a
                    href={href}
                    target="_blank"
                    rel="noopener noreferrer"
                    onClick={
                      native
                        ? (event) => {
                            event.preventDefault()
                            void request({
                              op: 'openReleaseLink',
                              url: href,
                            }).catch((error) => onError(error.message))
                          }
                        : undefined
                    }
                  >
                    {children}
                  </a>
                ) : (
                  <span>{children}</span>
                ),
            }}
          >
            {notes}
          </Markdown>
        ) : (
          <p>This release has no published notes.</p>
        )}
    </div>
  )
}

export function ReleaseNotes({
  notes,
  version,
  close,
  onError,
  title = 'What’s new',
}: {
  notes: string
  version: string
  close: () => void
  onError: (message: string) => void
  title?: string
}) {
  const pages = whatsNewPages(version)
  if (pages.length) {
    return <WhatsNewPages pages={pages} version={version} close={close} notesBody={<ReleaseMarkdown notes={notes} onError={onError} />} />
  }
  return (
    <Dialog
      title={title}
      eyebrow={`Keepfin ${version}`}
      labelledBy="release-notes-title"
      className="notes-dialog"
      onClose={close}
      footer={
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
      }
    >
      <ReleaseMarkdown notes={notes} onError={onError} />
    </Dialog>
  )
}
